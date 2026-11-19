"""로컬 부하 시험 클라이언트: 처음 보는 파일을 가상 에이전트 N명이 동시에 SubmitFile로 올린다.

make loadtest(infra/loadtest/run.sh)가 컨테이너 안에서 부른다. 직접 부를 일은 없다.

  loadtest.py wait                         api-server·toxiproxy가 응답할 때까지 기다린다
  loadtest.py seed --count N               무작위 악성 지문 N건을 관리 REST(블랙리스트 일괄 가져오기)로 넣는다
  loadtest.py bench --sizes .. --concs .. --bws .. --count .. --out DIR

출발점: 2026-11-19 운영 시나리오 D 스크립트(submit_bench.py). 64KiB 청크 스트리밍, 업로드마다 새 SHA, 제한 120초.

토큰(등록 토큰·에이전트 토큰)은 이 프로세스의 변수에만 둔다. 출력·로그·파일에 남기지 않는다.
"""
import argparse
import asyncio
import hashlib
import json
import os
import random
import resource
from concurrent.futures import ThreadPoolExecutor
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import uuid

import grpc
from teecher.agent.v1 import agent_pb2, agent_pb2_grpc
from teecher.verdict.v1 import verdict_pb2, verdict_pb2_grpc

# 같은 compose 네트워크 안의 주소. 관리 REST·등록은 운영처럼 nginx를 지나고, 측정 트래픽은 toxiproxy → nginx로 간다.
NGINX_HTTP = os.environ.get("LOADTEST_NGINX_HTTP", "http://nginx:80")
NGINX_GRPC = os.environ.get("LOADTEST_NGINX_GRPC", "nginx:80")
ACTUATOR = os.environ.get("LOADTEST_ACTUATOR", "http://api-server:8080/actuator")
TOXIPROXY_API = os.environ.get("LOADTEST_TOXIPROXY_API", "http://toxiproxy:8474")
PROXY_NAME = "grpc"
PROXY_LISTEN = "0.0.0.0:8666"
PROXY_TARGET = "toxiproxy:8666"
PROXY_UPSTREAM = "nginx:80"

CHUNK = 64 * 1024  # 에이전트와 같은 청크 크기
SEED_SOURCE = "LOADTEST"  # 블랙리스트 source(64자 이하). 운영 데이터와 구분하려고 따로 둔다
SEED_BATCH = 1000  # 서버 상한(ImportBlacklistBatchUseCase.MAX_ENTRIES)
SEED_RANDOM = 20261119  # 고정 시드: 다시 돌리면 같은 해시가 나와 서버가 건너뛴다(skippedActive)


def log(msg):
    print(msg, file=sys.stderr, flush=True)


# ---------------------------------------------------------------- HTTP

def http_json(method, url, body=None, timeout=30):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw and raw[:1] in b"{[" else raw.decode())
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, raw.decode(errors="replace")[:300]


def require_auth_disabled(status, what):
    if status in (401, 403):
        sys.exit(f"{what}: {status}. 관리 API 인증이 켜져 있다. 부하 시험은 infra/dev.env(AUTH_ENABLED=false)로 띄운 로컬 서버만 쓴다.")


# ---------------------------------------------------------------- wait

def cmd_wait(args):
    deadline = time.monotonic() + args.timeout
    while True:
        ok = []
        try:
            status, body = http_json("GET", ACTUATOR + "/health", timeout=5)
            ok.append(status == 200 and isinstance(body, dict) and body.get("status") == "UP")
        except OSError:
            ok.append(False)
        try:
            status, _ = http_json("GET", TOXIPROXY_API + "/version", timeout=5)
            ok.append(status == 200)
        except OSError:
            ok.append(False)
        if all(ok):
            log("서버 준비됨(api-server health UP, toxiproxy 응답)")
            return
        if time.monotonic() > deadline:
            sys.exit(f"{args.timeout}초 안에 서버가 준비되지 않았다. `make loadtest-logs`로 api-server 로그를 확인한다.")
        time.sleep(3)


# ---------------------------------------------------------------- seed

def seed_entries(count):
    """고정 시드 무작위 지문. DB CHECK: sha256 ^[0-9a-f]{64}$, tlsh ^T1[0-9A-F]{70}$."""
    rnd = random.Random(SEED_RANDOM)
    for i in range(count):
        sha = "%064x" % rnd.getrandbits(256)
        tlsh = "T1" + ("%070X" % rnd.getrandbits(280))
        yield {"sha256": sha, "tlsh": tlsh, "label": f"loadtest-{i}"}


def cmd_seed(args):
    entries = list(seed_entries(args.count))
    totals = {}
    t0 = time.monotonic()
    for i in range(0, len(entries), SEED_BATCH):
        batch = entries[i:i + SEED_BATCH]
        status, body = http_json("POST", NGINX_HTTP + "/api/admin/blacklist/import",
                                 {"source": SEED_SOURCE, "entries": batch}, timeout=300)
        require_auth_disabled(status, "블랙리스트 가져오기")
        if status != 200:
            sys.exit(f"블랙리스트 가져오기 실패({status}): {body}")
        for k, v in body.items():
            if isinstance(v, (int, float)):
                totals[k] = totals.get(k, 0) + v
        done = i + len(batch)
        if done % 10000 == 0 or done == len(entries):
            log(f"  시드 {done}/{len(entries)} ({time.monotonic() - t0:.0f}s)")
    log(f"시드 완료: {json.dumps(totals, ensure_ascii=False)}")


# ---------------------------------------------------------------- 지표(actuator)

def read_metrics():
    """메모리 TLSH corpus 상태. InMemoryTlshCorpus의 verdict.similarity.memory(.size)."""
    status, text = http_json("GET", ACTUATOR + "/prometheus", timeout=10)
    if status != 200 or not isinstance(text, str):
        return {}
    out = {"size": 0.0, "hit": 0.0, "stale": 0.0}
    for line in text.splitlines():
        if line.startswith("verdict_similarity_memory_size"):
            out["size"] += float(line.rsplit(" ", 1)[1])
        elif line.startswith("verdict_similarity_memory_total"):
            m = re.search(r'result="(\w+)"', line)
            if m and m.group(1) in out:
                out[m.group(1)] += float(line.rsplit(" ", 1)[1])
    return out


# ---------------------------------------------------------------- 인증

def register_agent():
    """관리 REST로 등록 토큰 → RegisterAgent → 에이전트 토큰. 반환값은 호출자 메모리에만 둔다."""
    status, body = http_json("POST", NGINX_HTTP + "/api/admin/agents/enrollment-tokens", {"expiresInMinutes": 10})
    require_auth_disabled(status, "등록 토큰 발급")
    if status != 201 or not isinstance(body, dict) or "token" not in body:
        sys.exit(f"등록 토큰 발급 실패({status})")
    with grpc.insecure_channel(NGINX_GRPC) as ch:
        resp = agent_pb2_grpc.AgentServiceStub(ch).RegisterAgent(agent_pb2.RegisterAgentRequest(
            enrollment_token=body["token"],
            hostname="loadtest-" + uuid.uuid4().hex[:8],
            os_platform=agent_pb2.OS_PLATFORM_LINUX,
            agent_version="loadtest",
            hardware_uuid=str(uuid.uuid4()),
        ), timeout=30)
    log(f"가상 에이전트 등록: agent_id={resp.agent_id}")
    return resp.agent_token


# ---------------------------------------------------------------- toxiproxy

def parse_bw(text):
    """'50mbit' → (표시 이름, toxiproxy rate KB/s 또는 None). tc와 같은 10진 단위: kbit=1e3, mbit=1e6, gbit=1e9 bit/s.

    toxiproxy bandwidth의 rate는 KB/s이고 1KB = 1000바이트다(toxics/bandwidth.go: 바이트 * 1ms / rate 만큼 잔다).
    """
    t = text.strip().lower()
    if t in ("", "0", "none", "off", "unlimited"):
        return "none", None
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(kbit|mbit|gbit)", t)
    if not m:
        sys.exit(f"BW 형식이 잘못됐다: {text!r} (예: 50mbit, 512kbit, 1gbit, none)")
    bits = float(m.group(1)) * {"kbit": 1e3, "mbit": 1e6, "gbit": 1e9}[m.group(2)]
    rate = round(bits / 8 / 1000)
    if rate < 1:
        sys.exit(f"BW가 너무 작다: {text!r} (최소 8kbit)")
    return t, rate


def ensure_proxy():
    status, _ = http_json("POST", TOXIPROXY_API + "/proxies",
                          {"name": PROXY_NAME, "listen": PROXY_LISTEN, "upstream": PROXY_UPSTREAM, "enabled": True})
    if status not in (201, 409):
        sys.exit(f"toxiproxy 프록시 생성 실패({status})")


def set_bandwidth(rate_kbps):
    # 업로드(클라이언트 → 서버) 방향만 늦춘다. 판정 응답(작다)은 그대로 둔다.
    http_json("DELETE", f"{TOXIPROXY_API}/proxies/{PROXY_NAME}/toxics/upload_bw")
    if rate_kbps is None:
        return
    status, body = http_json("POST", f"{TOXIPROXY_API}/proxies/{PROXY_NAME}/toxics", {
        "name": "upload_bw", "type": "bandwidth", "stream": "upstream", "toxicity": 1.0,
        "attributes": {"rate": rate_kbps}})
    if status != 200:
        sys.exit(f"toxiproxy bandwidth toxic 생성 실패({status}): {body}")


# ---------------------------------------------------------------- 업로드

class Payload:
    """크기 size의 무작위 본문. 업로드마다 마지막 32바이트만 바꿔 SHA가 매번 새롭다(캐시·블룸에 안 걸린다).

    원본 스크립트는 앞 32바이트를 바꿨다. 끝을 바꾸면 앞부분 해시 상태를 한 번만 계산하고 복사해 쓸 수 있어,
    100MB 파일에서도 업로드 직전 SHA 계산(이벤트 루프를 막아 다른 사용자 업로드를 늦춘다)이 없어진다.
    """

    TAIL = 32

    def __init__(self, size):
        if size < 64:
            raise ValueError("SIZE가 너무 작다")
        self.size = size
        self.base = memoryview(os.urandom(size - self.TAIL))
        self.head_hash = hashlib.sha256(self.base)

    def new_file(self):
        tail = os.urandom(self.TAIL)
        h = self.head_hash.copy()
        h.update(tail)
        return h.hexdigest(), tail

    def chunks(self, tail):
        base, n = self.base, len(self.base)
        for i in range(0, self.size, CHUNK):
            end = min(i + CHUNK, self.size)
            if end <= n:
                yield bytes(base[i:end])
            else:
                yield bytes(base[i:n]) + tail[max(0, i - n):end - n] if i < n else tail[i - n:end - n]


def new_channel(target):
    # 가상 사용자마다 자기 TCP 연결을 갖게 한다. gRPC는 같은 대상·옵션의 채널끼리 서브채널(연결)을 공유할 수 있어
    # 그러면 bandwidth toxic(연결마다 계산)이 여러 사용자에 나눠 걸린다. 로컬 서브채널 풀로 공유를 끈다.
    opts = [("grpc.use_local_subchannel_pool", 1), ("grpc.max_send_message_length", 8 * 1024 * 1024)]
    return grpc.aio.insecure_channel(target, options=opts)


async def upload_one(stub, payload, meta, timeout, label, epoch=None):
    sha, tail = payload.new_file()
    sent = {"t": None}
    t0 = time.monotonic()
    start = None if epoch is None else t0 - epoch

    async def requests():
        yield verdict_pb2.SubmitFileRequest(metadata=verdict_pb2.SubmitFileMetadata(
            sha256=sha, file_size=payload.size, filename=f"loadtest-{label}.bin",
            mime_type="application/octet-stream", event_id=str(uuid.uuid4())))
        for c in payload.chunks(tail):
            yield verdict_pb2.SubmitFileRequest(chunk=c)
        # 마지막 청크를 gRPC에 넘긴 시각. 전송 버퍼(HTTP/2 창·소켓·toxiproxy)만큼 실제 전송보다 이르다.
        sent["t"] = time.monotonic() - t0

    try:
        r = await stub.SubmitFile(requests(), timeout=timeout, metadata=meta)
        return {"ok": True, "start": start, "s": time.monotonic() - t0, "upload_s": sent["t"],
                "decision": verdict_pb2.Decision.Name(r.decision),
                "source": verdict_pb2.DecisionSource.Name(r.source)}
    except grpc.RpcError as e:
        return {"ok": False, "start": start, "s": time.monotonic() - t0, "upload_s": sent["t"],
                "code": e.code().name, "err": f"{e.code().name}: {(e.details() or '')[:160]}"}


async def run_round(payload, conc, total, meta, timeout, label):
    """total건을 conc명이 나눠 올린다. 결과의 seq는 시작 순서(워밍업 제외 기준)."""
    queue = asyncio.Queue()
    for i in range(total):
        queue.put_nowait(i)
    results = []
    t0 = time.monotonic()

    async def user():
        async with new_channel(PROXY_TARGET) as ch:
            stub = verdict_pb2_grpc.VerdictServiceStub(ch)
            while True:
                try:
                    seq = queue.get_nowait()
                except asyncio.QueueEmpty:
                    return
                r = await upload_one(stub, payload, meta, timeout, label, epoch=t0)
                r["seq"] = seq
                results.append(r)

    await asyncio.gather(*[user() for _ in range(conc)])
    results.sort(key=lambda r: r["seq"])
    return results


def pct(values, p):
    if not values:
        return None
    v = sorted(values)
    return v[min(len(v) - 1, max(0, int(round(p * len(v) + 0.5)) - 1))]  # nearest-rank


def summarize(size_mb, conc, bw, rate, results, warmup, timeout, slo):
    """앞의 warmup건(시작 순서)은 집계에서 뺀다. 처리량은 첫 집계 건 시작부터 마지막 집계 건 끝까지로 나눈다."""
    measured = [r for r in results if r["seq"] >= warmup]
    ok = [r for r in measured if r["ok"]]
    t = [r["s"] for r in ok]
    up = [r["upload_s"] for r in ok if r["upload_s"] is not None]
    deadline = [r for r in measured if not r["ok"] and r.get("code") == "DEADLINE_EXCEEDED"]
    over_timeout = len(deadline) + sum(1 for r in ok if r["s"] > timeout)
    over_slo = sum(1 for r in measured if r["s"] > slo)
    wall = (max(r["start"] + r["s"] for r in measured) - min(r["start"] for r in measured)) if measured else 0
    return {
        "size_mb": size_mb, "conc": conc, "bw": bw, "bw_rate_kBps": rate,
        "count": len(measured), "warmup_excluded": len(results) - len(measured),
        "ok": len(ok), "fail": len(measured) - len(ok),
        "slo_s": slo, "over_slo": over_slo, "timeout_s": timeout, "over_timeout": over_timeout,
        "p50": pct(t, 0.50), "p95": pct(t, 0.95), "p99": pct(t, 0.99), "max": max(t) if t else None,
        "upload_p50": pct(up, 0.50), "upload_min": min(up) if up else None,
        "wall_s": wall, "files_per_s": len(ok) / wall if wall else None,
        "mb_per_s": len(ok) * size_mb / wall if wall else None,
        "decisions": sorted({f'{r["decision"]}/{r["source"]}' for r in ok}),
        "errors": sorted({r["err"] for r in measured if not r["ok"]}),
    }


def fmt(v, digits=2):
    return "-" if v is None else f"{v:.{digits}f}"


def print_table(rows):
    slo = rows[0]["slo_s"] if rows else 10
    timeout = rows[0]["timeout_s"] if rows else 120
    head = ["크기MB", "동시", "회선", "건수", "워밍업제외", "성공", "실패", "p50(s)", "p95(s)", "p99(s)", "max(s)",
            f"{slo:g}s초과", f"{timeout:g}s초과", "업로드p50", "MB/s"]
    lines = [[f'{r["size_mb"]:g}', str(r["conc"]), r["bw"], str(r["count"]), str(r["warmup_excluded"]),
              str(r["ok"]), str(r["fail"]), fmt(r["p50"]), fmt(r["p95"]), fmt(r["p99"]), fmt(r["max"]),
              str(r["over_slo"]), str(r["over_timeout"]), fmt(r["upload_p50"]), fmt(r["mb_per_s"])] for r in rows]
    widths = [max(display_width(h), *(display_width(l[i]) for l in lines)) + 1 for i, h in enumerate(head)]
    out = ["".join(rjust_w(h, w) for h, w in zip(head, widths))]
    out += ["".join(rjust_w(c, w) for c, w in zip(l, widths)) for l in lines]
    print("\n".join(out), flush=True)
    print("p50~max는 워밍업을 뺀 성공 건만 센다. 초과 건수는 실패(제한 시간 도달)를 포함한다.", flush=True)


# ---------------------------------------------------------------- RATE(개방형 도착)

def arrival_times(rate, duration, count, kind, seed):
    """예정 시작 시각(초) 목록. poisson은 지수 분포 간격, uniform은 1/rate 고정 간격. count>0이면 건수로, 아니면 duration까지."""
    rnd = random.Random(seed)
    times, t = [], 0.0
    while True:
        t += rnd.expovariate(rate) if kind == "poisson" else 1.0 / rate
        if (count > 0 and len(times) >= count) or (count <= 0 and t > duration):
            return times
        times.append(t)


async def run_open(payload, rate, schedule, meta, timeout, max_inflight, label):
    """예정 시각에 업로드를 시작한다. 앞 업로드가 끝나기를 기다리지 않는다(서버가 느려지면 동시 진행 수가 는다).

    지연은 예정 시작 시각부터 잰다(coordinated omission 방지). 클라이언트가 예정보다 늦게 시작한 만큼(lag)을 따로 남겨
    클라이언트 병목과 서버 병목을 구분한다. 업로드마다 채널(=TCP 연결)을 새로 열어 회선 제한이 업로드 1건(가상 사용자 1명)에 걸린다.
    """
    results, tasks, samples = [], [], []
    inflight = {"n": 0}
    t0 = time.monotonic()

    async def one(seq, sched):
        lag = time.monotonic() - t0 - sched
        inflight["n"] += 1
        try:
            async with new_channel(PROXY_TARGET) as ch:
                r = await upload_one(verdict_pb2_grpc.VerdictServiceStub(ch), payload, meta, timeout, label)
        finally:
            inflight["n"] -= 1
        end = time.monotonic() - t0
        r.update(seq=seq, sched=sched, start=sched + lag, lag=lag, s=end - sched, service_s=r["s"])
        results.append(r)

    async def sampler():
        while True:
            samples.append((time.monotonic() - t0, inflight["n"]))
            await asyncio.sleep(0.5)

    sampling = asyncio.create_task(sampler())
    for seq, sched in enumerate(schedule):
        delay = sched - (time.monotonic() - t0)
        if delay > 0:
            await asyncio.sleep(delay)
        if inflight["n"] >= max_inflight:
            # 동시 진행 상한: 보내지 않고 클라이언트 측 거절로 센다(PC가 멈추지 않게).
            results.append({"ok": False, "rejected": True, "seq": seq, "sched": sched, "start": None, "lag": None,
                            "s": None, "code": "CLIENT_REJECTED", "err": f"CLIENT_REJECTED: 동시 진행 {max_inflight}건 상한"})
            continue
        tasks.append(asyncio.create_task(one(seq, sched)))
    await asyncio.gather(*tasks)
    sampling.cancel()
    results.sort(key=lambda r: r["seq"])
    return results, samples


def summarize_open(size_mb, target_rate, bw, rate, results, samples, warmup, timeout, slo, lag_threshold=0.1):
    measured = [r for r in results if r["seq"] >= warmup]
    sent = [r for r in measured if not r.get("rejected")]
    ok = [r for r in sent if r["ok"]]
    t = [r["s"] for r in ok]
    lags = [r["lag"] for r in sent]
    deadline = [r for r in sent if not r["ok"] and r.get("code") == "DEADLINE_EXCEEDED"]
    over_timeout = len(deadline) + sum(1 for r in ok if r["s"] > timeout)
    over_slo = sum(1 for r in sent if r["s"] > slo)
    first = min((r["sched"] for r in measured), default=0)
    starts = sorted(r["start"] for r in sent)
    achieved = (len(starts) - 1) / (starts[-1] - starts[0]) if len(starts) > 1 and starts[-1] > starts[0] else None
    end = max((r["start"] + r["service_s"] for r in sent), default=first)
    wall = end - first
    window = [n for (ts, n) in samples if first <= ts <= end]

    # 시간 흐름: 집계 구간을 예정 시각 기준 3등분해 지연 중앙값·최대 동시 진행 수를 본다(처리량을 넘으면 뒤로 갈수록 커진다).
    trend = []
    if measured:
        last = max(r["sched"] for r in measured)
        span = max(last - first, 1e-9)
        for i in range(3):
            lo, hi = first + span * i / 3, first + span * (i + 1) / 3
            part = [r["s"] for r in ok if lo <= r["sched"] <= hi]
            infl = [n for (ts, n) in samples if lo <= ts <= hi]
            trend.append({"from_s": lo, "to_s": hi, "n": len(part), "p50": pct(part, 0.5),
                          "max": max(part) if part else None, "inflight_max": max(infl) if infl else None})
    return {
        "mode": "rate", "size_mb": size_mb, "target_rate": target_rate, "achieved_rate": achieved,
        "bw": bw, "bw_rate_kBps": rate,
        "count": len(measured), "warmup_excluded": len(results) - len(measured),
        "ok": len(ok), "fail": len(sent) - len(ok), "rejected": len(measured) - len(sent),
        "slo_s": slo, "over_slo": over_slo, "over_slo_pct": 100.0 * over_slo / len(sent) if sent else None,
        "timeout_s": timeout, "over_timeout": over_timeout,
        "p50": pct(t, 0.50), "p95": pct(t, 0.95), "p99": pct(t, 0.99), "max": max(t) if t else None,
        "inflight_max": max(window) if window else None,
        "inflight_avg": sum(window) / len(window) if window else None,
        "client_late": sum(1 for x in lags if x > lag_threshold), "client_lag_max": max(lags) if lags else None,
        "wall_s": wall, "mb_per_s": len(ok) * size_mb / wall if wall else None,
        "trend": trend,
        "decisions": sorted({f'{r["decision"]}/{r["source"]}' for r in ok}),
        "errors": sorted({r["err"] for r in measured if not r["ok"]}),
    }


def print_rate_table(rows):
    slo = rows[0]["slo_s"]
    timeout = rows[0]["timeout_s"]
    head = ["크기MB", "회선", "목표/s", "달성/s", "건수", "워밍업제외", "성공", "실패", "거절", "p50(s)", "p95(s)",
            "p99(s)", "max(s)", f"{slo:g}s초과%", f"{timeout:g}s초과", "동시max", "동시avg", "늦은시작", "최대지연(s)", "MB/s"]
    lines = [[f'{r["size_mb"]:g}', r["bw"], f'{r["target_rate"]:g}', fmt(r["achieved_rate"]), str(r["count"]),
              str(r["warmup_excluded"]), str(r["ok"]), str(r["fail"]), str(r["rejected"]), fmt(r["p50"]),
              fmt(r["p95"]), fmt(r["p99"]), fmt(r["max"]), fmt(r["over_slo_pct"], 1), str(r["over_timeout"]),
              str(r["inflight_max"]), fmt(r["inflight_avg"], 1), str(r["client_late"]), fmt(r["client_lag_max"]),
              fmt(r["mb_per_s"])] for r in rows]
    widths = [max(display_width(h), *(display_width(l[i]) for l in lines)) + 1 for i, h in enumerate(head)]
    out = ["".join(rjust_w(h, w) for h, w in zip(head, widths))]
    out += ["".join(rjust_w(c, w) for c, w in zip(l, widths)) for l in lines]
    print("\n".join(out))
    print("지연은 예정 시작 시각 기준. 늦은시작 = 예정보다 0.1초 넘게 늦게 보낸 건(클라이언트 밀림). 거절 = 동시 진행 상한 초과.")
    print("시간 흐름(집계 구간 3등분: 지연 p50 / 최대 지연 / 동시 진행 최대):")
    for r in rows:
        parts = [f'{fmt(w["p50"])}/{fmt(w["max"])}/{w["inflight_max"]}' for w in r["trend"]]
        print(f'  {r["size_mb"]:g}MB {r["bw"]} {r["target_rate"]:g}/s: ' + "  →  ".join(parts))
    sys.stdout.flush()



async def warm_up(meta, timeout, wait_s):
    """메모리 TLSH corpus가 지금 토큰과 맞을 때까지 작은 파일을 올린다. 시드 직후에는 재구축(최소 30초 간격)을 기다린다."""
    payload = Payload(64 * 1024)
    deadline = time.monotonic() + wait_s
    async with new_channel(NGINX_GRPC) as ch:
        stub = verdict_pb2_grpc.VerdictServiceStub(ch)
        while True:
            before = read_metrics()
            r = await upload_one(stub, payload, meta, timeout, "warmup")
            after = read_metrics()
            if not r["ok"]:
                sys.exit(f"준비 업로드 실패: {r['err']}")
            if after.get("hit", 0) > before.get("hit", 0):
                return after
            if time.monotonic() > deadline:
                log("경고: 메모리 TLSH corpus가 아직 최신이 아니다(DB 경로로 비교 중). 결과에 corpus_memory_ready=false로 남긴다.")
                return dict(after, not_ready=True)
            log("  메모리 TLSH corpus 재구축 대기 중...")
            await asyncio.sleep(5)


async def bench(args):
    sizes = [float(s) for s in args.sizes.split(",") if s.strip()]
    concs = [int(c) for c in args.concs.split(",") if c.strip()]
    rates = [float(r) for r in args.rates.split(",") if r.strip()]
    if rates and args.concs.strip():
        sys.exit("CONC와 RATE는 함께 쓸 수 없다")
    if not rates and not concs:
        concs = [1, 2, 4, 8]
    if any(r <= 0 for r in rates):
        sys.exit("RATE는 0보다 커야 한다")
    bws = [parse_bw(b) for b in args.bws.split(",")] if args.bws.strip() else [parse_bw("none")]
    os.makedirs(args.out, exist_ok=True)

    ensure_proxy()
    token = register_agent()
    meta = (("authorization", "Bearer " + token),)
    del token

    corpus = await warm_up(meta, args.timeout, args.corpus_wait)
    log(f"메모리 TLSH corpus: 크기 {corpus.get('size', 0):.0f}건, 최신={'아니오' if corpus.get('not_ready') else '예'}")

    rows, rate_rows = [], []
    for size_mb in sizes:
        payload = Payload(int(size_mb * 1024 * 1024))
        for bw, rate in bws:
            set_bandwidth(rate)
            for conc in concs:
                count = args.count if args.count > 0 else (max(5, 3 * conc) if args.quick else 300)
                warmup = conc if args.warmup < 0 else args.warmup
                started = time.monotonic()
                log(f"측정: {size_mb:g}MB × {count}건(+워밍업 {warmup}건), 동시 {conc}, 회선 {bw}"
                    + (f" (사용자당 {rate} KB/s = {rate * 8 / 1000:g} Mbit/s)" if rate else ""))
                results = await run_round(payload, conc, warmup + count, meta, args.timeout, f"{size_mb:g}mb")
                s = summarize(size_mb, conc, bw, rate, results, warmup, args.timeout, args.slo)
                s["elapsed_s"] = time.monotonic() - started
                rows.append(s)
                name = f"size{size_mb:g}mb_c{conc}_bw{bw}.json"
                with open(os.path.join(args.out, name), "w") as f:
                    json.dump({"summary": s, "results": results}, f, ensure_ascii=False, indent=1)
                log("  → " + json.dumps({k: s[k] for k in ("count", "ok", "fail", "over_slo", "over_timeout",
                                                           "p50", "p95", "p99", "max", "upload_p50", "mb_per_s",
                                                           "elapsed_s")}, ensure_ascii=False))
            for target in rates:
                duration = args.duration if args.duration > 0 else (30 if args.quick else 180)
                # 워밍업: 명시하면 앞 N건, 아니면 처음 10초에 예정된 건(최소 1건).
                if args.warmup >= 0:
                    warmup = args.warmup
                else:
                    warmup = max(1, sum(1 for x in arrival_times(target, 10, 0, args.arrival, args.arrival_seed)))
                if args.count > 0:
                    schedule = arrival_times(target, 0, warmup + args.count, args.arrival, args.arrival_seed)
                else:
                    schedule = arrival_times(target, duration + (10 if args.warmup < 0 else 0), 0,
                                             args.arrival, args.arrival_seed)
                started = time.monotonic()
                log(f"측정(RATE): {size_mb:g}MB, 초당 {target:g}건 {args.arrival} 도착, 예정 {len(schedule)}건"
                    f"(앞 {warmup}건 워밍업), 약 {schedule[-1] if schedule else 0:.0f}초, 회선 {bw}"
                    + (f" (업로드 1건당 {rate} KB/s = {rate * 8 / 1000:g} Mbit/s)" if rate else ""))
                results, samples = await run_open(payload, target, schedule, meta, args.timeout,
                                                  args.max_inflight, f"{size_mb:g}mb")
                s = summarize_open(size_mb, target, bw, rate, results, samples, warmup, args.timeout, args.slo)
                s["arrival"] = args.arrival
                s["arrival_seed"] = args.arrival_seed
                s["elapsed_s"] = time.monotonic() - started
                rate_rows.append(s)
                name = f"size{size_mb:g}mb_rate{target:g}_bw{bw}.json"
                with open(os.path.join(args.out, name), "w") as f:
                    json.dump({"summary": s, "inflight_samples": samples, "results": results}, f,
                              ensure_ascii=False, indent=1)
                log("  → " + json.dumps({k: s[k] for k in ("achieved_rate", "count", "ok", "fail", "rejected",
                                                           "p50", "p95", "p99", "max", "over_slo_pct",
                                                           "inflight_max", "client_late", "mb_per_s", "elapsed_s")},
                                        ensure_ascii=False))
        set_bandwidth(None)

    env = {k[len("LOADTEST_ENV_"):].lower(): v for k, v in os.environ.items() if k.startswith("LOADTEST_ENV_")}
    with open(os.path.join(args.out, "summary.json"), "w") as f:
        json.dump({"env": env, "corpus_memory": corpus, "rows": rows, "rate_rows": rate_rows}, f,
                  ensure_ascii=False, indent=1)
    print()
    if rows:
        print_table(rows)
    if rate_rows:
        print_rate_table(rate_rows)
    print(f"\n결과: {args.out_display or args.out}")


# ---------------------------------------------------------------- BURST(한꺼번에 N건)

class HeadPayload:
    """버스트용 본문. 공유 기준 버퍼 하나 + 업로드마다 다른 첫 32바이트. N×크기를 미리 만들지 않는다.

    업로드마다 SHA256(앞 32바이트 + 기준[32:])을 측정 전에(배리어 전에) 스레드로 계산해 둔다(hashlib은 GIL을 놓는다).
    """

    HEAD = 32

    def __init__(self, size):
        if size < 64:
            raise ValueError("SIZE가 너무 작다")
        self.size = size
        self.base = memoryview(os.urandom(size))

    def prepare(self, n):
        rest = self.base[self.HEAD:]

        def one(_):
            head = os.urandom(self.HEAD)
            h = hashlib.sha256(head)
            h.update(rest)
            return h.hexdigest(), head

        with ThreadPoolExecutor(max_workers=min(8, os.cpu_count() or 2)) as pool:
            return list(pool.map(one, range(n)))

    def chunks(self, head):
        first_end = min(CHUNK, self.size)
        yield head + bytes(self.base[self.HEAD:first_end])
        for i in range(first_end, self.size, CHUNK):
            yield bytes(self.base[i:min(i + CHUNK, self.size)])


async def burst_upload(ch, payload, sha, head, meta, timeout, barrier, t0_box, label):
    """배리어가 열리면 시작한다. 시각은 배리어 해제 기준 초: 시작, 첫 청크를 gRPC가 가져감, 마지막 청크, 판정 수신."""
    stub = verdict_pb2_grpc.VerdictServiceStub(ch)
    mark = {}
    await barrier.wait()
    t0 = t0_box["t0"]
    mark["start"] = time.monotonic() - t0

    async def requests():
        yield verdict_pb2.SubmitFileRequest(metadata=verdict_pb2.SubmitFileMetadata(
            sha256=sha, file_size=payload.size, filename=f"loadtest-burst-{label}.bin",
            mime_type="application/octet-stream", event_id=str(uuid.uuid4())))
        first = True
        for c in payload.chunks(head):
            yield verdict_pb2.SubmitFileRequest(chunk=c)
            if first:
                # 다음 메시지를 요청받았다 = 첫 청크(첫 64KiB)를 gRPC가 전송 버퍼로 가져갔다.
                mark["first_chunk"] = time.monotonic() - t0
                first = False
        mark["upload_done"] = time.monotonic() - t0

    r = {"sha256": sha}
    try:
        resp = await stub.SubmitFile(requests(), timeout=timeout, metadata=meta)
        r.update(ok=True, decision=verdict_pb2.Decision.Name(resp.decision),
                 source=verdict_pb2.DecisionSource.Name(resp.source))
    except grpc.RpcError as e:
        r.update(ok=False, code=e.code().name, err=f"{e.code().name}: {(e.details() or '')[:160]}")
    mark["verdict"] = time.monotonic() - t0
    r.update(mark)
    r["s"] = mark["verdict"] - mark["start"]
    return r


def display_width(text):
    """터미널 표시 폭(한글은 2칸)."""
    return sum(2 if unicodedata.east_asian_width(c) in ("W", "F") else 1 for c in text)


def rjust_w(text, width):
    return " " * (width - display_width(text)) + text


def max_overlap(intervals):
    events = sorted([(a, 1) for a, b in intervals] + [(b, -1) for a, b in intervals], key=lambda e: (e[0], e[1]))
    cur = best = 0
    for _, d in events:
        cur += d
        best = max(best, cur)
    return best


async def burst(args):
    size_mb = float(args.size)
    n = args.burst
    bw, rate = parse_bw(args.bw)
    os.makedirs(args.out, exist_ok=True)

    ensure_proxy()
    token = register_agent()
    meta = (("authorization", "Bearer " + token),)
    del token
    corpus = await warm_up(meta, args.timeout, args.corpus_wait)
    log(f"메모리 TLSH corpus: 크기 {corpus.get('size', 0):.0f}건, 최신={'아니오' if corpus.get('not_ready') else '예'}")
    set_bandwidth(rate)

    payload = HeadPayload(int(size_mb * 1024 * 1024))
    t = time.monotonic()
    files = payload.prepare(n)
    log(f"파일 {n}개 준비(서로 다른 SHA {len({f[0] for f in files})}개, 해시 계산 {time.monotonic() - t:.1f}s)")

    # 업로드마다 채널(=TCP 연결 1개)을 따로 연다. 한 연결에 몰면 HTTP/2 동시 스트림 한도(nginx 기본 128)에 걸려 클라이언트에서 줄을 선다.
    channels = [new_channel(PROXY_TARGET) for _ in range(n)]
    t = time.monotonic()
    ready = await asyncio.gather(*[asyncio.wait_for(ch.channel_ready(), 60) for ch in channels], return_exceptions=True)
    connected = sum(1 for x in ready if not isinstance(x, BaseException))
    log(f"연결 {connected}/{n}개 수립({time.monotonic() - t:.1f}s)")

    barrier = asyncio.Event()
    t0_box = {}
    tasks = [asyncio.create_task(burst_upload(ch, payload, sha, head, meta, args.timeout, barrier, t0_box,
                                              f"{size_mb:g}mb")) for ch, (sha, head) in zip(channels, files)]
    await asyncio.sleep(0.5)  # 모든 태스크가 배리어 앞에 도착하게
    log(f"{n}건 동시 시작: {size_mb:g}MB, 회선 {bw}")
    wall_start = time.time()
    t0_box["t0"] = time.monotonic()
    barrier.set()
    results = await asyncio.gather(*tasks)
    for ch in channels:
        await ch.close()
    set_bandwidth(None)

    ok = [r for r in results if r["ok"]]
    lat = [r["s"] for r in ok]
    codes = {}
    for r in results:
        if not r["ok"]:
            codes[r["code"]] = codes.get(r["code"], 0) + 1
    sources = {}
    for r in ok:
        sources[r["source"]] = sources.get(r["source"], 0) + 1
    engine_path = sum(v for k, v in sources.items() if k in ("DECISION_SOURCE_ENGINE", "DECISION_SOURCE_SIMILARITY"))
    started = [(r["first_chunk"], r["verdict"]) for r in results if "first_chunk" in r]
    last = max(r["verdict"] for r in results) if results else 0
    summary = {
        "mode": "burst", "size_mb": size_mb, "burst": n, "bw": bw, "bw_rate_kBps": rate,
        "connected_before_start": connected, "wall_start_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(wall_start)),
        "ok": len(ok), "fail": len(results) - len(ok), "fail_by_code": codes, "ok_by_source": sources,
        "engine_path_ok": engine_path,
        "first_verdict_s": min(lat) if lat else None, "last_verdict_s": last,
        "p50": pct(lat, 0.50), "p95": pct(lat, 0.95), "p99": pct(lat, 0.99), "max": max(lat) if lat else None,
        "slo_s": args.slo, "over_slo": sum(1 for r in results if r["s"] > args.slo),
        "timeout_s": args.timeout,
        "over_timeout": sum(1 for r in results if r.get("code") == "DEADLINE_EXCEEDED" or r["s"] > args.timeout),
        "mb_per_s": len(ok) * size_mb / last if last else None,
        "concurrent_max": max_overlap(started),
        "first_chunk_sent_max_s": max((r["first_chunk"] for r in results if "first_chunk" in r), default=None),
        "upload_done_p50_s": pct([r["upload_done"] for r in results if "upload_done" in r], 0.5),
        "client_peak_rss_mb": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024,
        "corpus_memory": corpus,
        "env": {k[len("LOADTEST_ENV_"):].lower(): v for k, v in os.environ.items() if k.startswith("LOADTEST_ENV_")},
        "errors": sorted({r["err"] for r in results if not r["ok"]}),
    }
    with open(os.path.join(args.out, "burst.json"), "w") as f:
        json.dump({"summary": summary, "results": results}, f, ensure_ascii=False, indent=1)
    with open(os.path.join(args.out, "burst_shas.txt"), "w") as f:
        f.write("\n".join(r["sha256"] for r in results) + "\n")

    print()
    rows = [
        ("건수", f"{n} ({size_mb:g}MB, 회선 {bw}, 시작 전 연결 {connected}개)"),
        ("성공 / 실패", f"{len(ok)} / {len(results) - len(ok)}"),
        ("실패 코드별", ", ".join(f"{k} {v}" for k, v in sorted(codes.items())) or "-"),
        ("판정 출처(성공)", ", ".join(f"{k.replace('DECISION_SOURCE_', '')} {v}" for k, v in sorted(sources.items())) or "-"),
        ("YARA 경로(응답 출처 ENGINE/SIMILARITY)", f"{engine_path} / {n}"),
        ("첫 판정까지", fmt(summary["first_verdict_s"]) + "s"),
        ("마지막 판정까지(전체 완료)", fmt(summary["last_verdict_s"]) + "s"),
        ("p50 / p95 / p99 / max", " / ".join(fmt(summary[k]) for k in ("p50", "p95", "p99", "max")) + " s (성공 건)"),
        (f"{args.slo:g}초 초과", f'{summary["over_slo"]}건 (실패 포함)'),
        (f"{args.timeout:g}초 초과(에이전트 타임아웃)", f'{summary["over_timeout"]}건'),
        ("전체 처리량", fmt(summary["mb_per_s"]) + " MB/s (성공 건 × 크기 ÷ 마지막 판정 시각)"),
        ("실제 동시 진행 최대", f'{summary["concurrent_max"]}건 (첫 청크 전송 ~ 판정 수신 구간이 겹친 최대)'),
        ("마지막 업로드가 첫 청크를 보낸 시각", fmt(summary["first_chunk_sent_max_s"]) + "s"),
        ("클라이언트 최대 메모리(RSS)", f'{summary["client_peak_rss_mb"]:.0f}MB'),
    ]
    w = max(display_width(k) for k, _ in rows) + 2
    for k, v in rows:
        print(k + " " * (w - display_width(k)) + v)
    print(f"\n결과: {args.out_display or args.out}", flush=True)


# ---------------------------------------------------------------- verify-seed

def tlsh_variant(tlsh):
    """본문 마지막 hex 한 자리를 바꾼 가까운 변종(TLSH 거리 수 이내). 체크섬·L·Q는 그대로 둔다."""
    last = tlsh[-1]
    return tlsh[:-1] + ("0" if last != "0" else "1")


async def cmd_verify_seed(args):
    """시드 반영 확인: 메모리 corpus 크기, 시드 SHA256의 CheckHash BLOCK, 가까운 TLSH 변종의 유사도 판정."""
    token = register_agent()
    meta = (("authorization", "Bearer " + token),)
    del token
    failures = []
    async with new_channel(NGINX_GRPC) as ch:
        stub = verdict_pb2_grpc.VerdictServiceStub(ch)

        async def check(sha, tlsh=""):
            r = await stub.CheckHash(verdict_pb2.CheckHashRequest(
                sha256=sha, file_size=1024, tlsh=tlsh, event_id=str(uuid.uuid4())), timeout=30, metadata=meta)
            return verdict_pb2.Decision.Name(r.decision), verdict_pb2.DecisionSource.Name(r.source), r.reason

        # 1) 메모리 corpus: 재기동 직후 warmUp이 백그라운드로 읽는다. 다 읽을 때까지 기다린다.
        deadline = time.monotonic() + args.timeout
        t = time.monotonic()
        while True:
            size = read_metrics().get("size", 0)
            if size >= args.expect_corpus or time.monotonic() > deadline:
                break
            await asyncio.sleep(3)
        ok = size >= args.expect_corpus
        log(f"메모리 TLSH corpus 크기: {size:.0f}건 (기대 {args.expect_corpus}건, {time.monotonic() - t:.0f}s 대기) "
            + ("OK" if ok else "실패"))
        if not ok:
            failures.append("corpus")

        # 2) 시드 SHA256 → 블룸 필터 통과 → 블랙리스트 정확 일치 → BLOCK. 블룸 재구축 전이면 UNKNOWN이라 잠시 다시 본다.
        while True:
            d, src, reason = await check(args.sha)
            if d == "DECISION_BLOCK" or time.monotonic() > deadline:
                break
            await asyncio.sleep(3)
        ok = d == "DECISION_BLOCK" and src == "DECISION_SOURCE_BLACKLIST"
        log(f"시드 SHA256 CheckHash: {d}/{src} ({reason}) " + ("OK" if ok else "실패"))
        if not ok:
            failures.append("blacklist")

        # 3) 처음 보는 SHA256 + 시드 TLSH의 가까운 변종 → TLSH 유사도 BLOCK.
        variant = tlsh_variant(args.tlsh)
        d, src, reason = await check("%064x" % random.getrandbits(256), variant)
        ok = d == "DECISION_BLOCK" and src == "DECISION_SOURCE_SIMILARITY"
        log(f"변종 TLSH CheckHash: {d}/{src} ({reason}) " + ("OK" if ok else "실패"))
        if not ok:
            failures.append("similarity")
    if failures:
        sys.exit("반영 확인 실패: " + ", ".join(failures))
    log("반영 확인 모두 통과")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    w = sub.add_parser("wait")
    w.add_argument("--timeout", type=int, default=300)
    s = sub.add_parser("seed")
    s.add_argument("--count", type=int, required=True)
    b = sub.add_parser("bench")
    b.add_argument("--sizes", default="10")
    b.add_argument("--concs", default="", help="닫힌 루프 동시 수(쉼표). RATE와 함께 못 쓴다. 둘 다 없으면 1,2,4,8")
    b.add_argument("--rates", default="", help="개방형 도착: 초당 업로드 시작 건수(쉼표, 소수 가능)")
    b.add_argument("--duration", type=float, default=0, help="RATE 측정 시간(초). 0이면 180(--quick이면 30)")
    b.add_argument("--arrival", choices=["poisson", "uniform"], default="poisson")
    b.add_argument("--arrival-seed", type=int, default=1)
    b.add_argument("--max-inflight", type=int, default=200)
    b.add_argument("--bws", default="")
    b.add_argument("--count", type=int, default=0,
                   help="조합마다 집계할 업로드 수. 0이면 300(--quick이면 max(5, 3×동시))")
    b.add_argument("--quick", action="store_true", help="빠른 확인: 조합당 소수 건")
    b.add_argument("--warmup", type=int, default=-1,
                   help="조합마다 집계에서 뺄 앞 업로드 수(추가로 올린다). -1이면 CONC는 동시 수만큼, RATE는 처음 10초분. 0이면 끈다")
    b.add_argument("--slo", type=float, default=10.0, help="이 초를 넘은 건수를 따로 센다")
    b.add_argument("--timeout", type=float, default=120.0, help="에이전트 대기 한도(초)")
    b.add_argument("--corpus-wait", type=int, default=120)
    b.add_argument("--out", required=True)
    b.add_argument("--out-display", default="")
    u = sub.add_parser("burst")
    u.add_argument("--size", default="10")
    u.add_argument("--burst", type=int, default=300)
    u.add_argument("--bw", default="none")
    u.add_argument("--slo", type=float, default=10.0)
    u.add_argument("--timeout", type=float, default=120.0)
    u.add_argument("--corpus-wait", type=int, default=120)
    u.add_argument("--out", required=True)
    u.add_argument("--out-display", default="")
    v = sub.add_parser("verify-seed")
    v.add_argument("--sha", required=True)
    v.add_argument("--tlsh", required=True)
    v.add_argument("--expect-corpus", type=int, required=True)
    v.add_argument("--timeout", type=int, default=600)
    a = p.parse_args()
    if a.cmd == "wait":
        cmd_wait(a)
    elif a.cmd == "seed":
        cmd_seed(a)
    elif a.cmd == "verify-seed":
        asyncio.run(cmd_verify_seed(a))
    elif a.cmd == "burst":
        asyncio.run(burst(a))
    else:
        asyncio.run(bench(a))


if __name__ == "__main__":
    main()
