"""위협 대시보드 API 응답 시간 측정 클라이언트.

경로와 쿼리 파라미터는 콘솔(console/src/lib/dashboard/load.ts, lib/api/dashboard.ts, lib/api/events.ts)이
보내는 값 그대로다. 읽기만 잰다.

시나리오
- baseline: 엔드포인트를 하나씩, 동시 1개로, 기간 1·7·30·90일마다 잰다.
- polling:  관리자 N명이 5초마다 화면 전체 요청(콘솔이 한 번에 보내는 8개)을 동시에 보낸다.
            라운드 지연 = 그 라운드 응답 중 가장 늦은 것. 콘솔 usePolling처럼 라운드가 끝난 뒤 5초를 쉬고 다음 라운드를
            시작하므로 라운드는 겹치지 않는다. 라운드 전체(8개 요청)에 총 8초 상한을 건다(콘솔 AbortSignal.timeout).
요약의 screen 행 errors는 오류가 난 라운드 수다(기준선 행의 errors는 오류 난 요청 수).
"""
import argparse
import asyncio
import csv
import math
import os
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

import httpx

# 콘솔 DashboardPage.tsx와 같은 값
POLL_INTERVAL_S = 5.0
REQUEST_TIMEOUT_S = 8.0   # 폴링: 콘솔과 같은 요청 상한
BASELINE_TIMEOUT_S = 120.0  # 기준선: 느린 쿼리의 실제 시간을 보려고 상한을 크게 둔다(넘으면 오류, ms=상한)
DEFAULT_PERIOD_DAYS = 7  # 콘솔 기본 기간 선택값('7d')
THREAT_FETCH_SIZE = 50
TOP_SOURCES_SIZE = 5
RECENT_BLOCKS_SIZE = 10
UNACK_SIZE = 5
TZ = "Asia/Seoul"

SAMPLE_FIELDS = ["scenario", "endpoint", "period_days", "admins", "ms", "status"]
ROUND_FIELDS = ["scenario", "endpoint", "period_days", "admins", "admin", "round", "ms", "errors"]
SUMMARY_FIELDS = ["endpoint", "period_days", "admins", "n", "p50_ms", "p95_ms", "max_ms", "errors"]


@dataclass(frozen=True)
class Endpoint:
    name: str
    path: str
    params: Callable[[dict], dict]  # {"from","to"} -> 쿼리 파라미터. 값이 리스트면 같은 키를 반복해 보낸다.
    uses_period: bool = True


# 콘솔 loadDashboard()가 한 번에 보내는 8개 요청(top-sources는 AGENT·HOST 둘 다).
ENDPOINTS: list[Endpoint] = [
    Endpoint("summary", "/api/admin/dashboard/summary", lambda p: {"from": p["from"], "to": p["to"]}),
    Endpoint("unacknowledged-blocks", "/api/admin/dashboard/unacknowledged-blocks",
             lambda p: {"size": UNACK_SIZE}, uses_period=False),
    Endpoint("threats", "/api/admin/dashboard/threats",
             lambda p: {"from": p["from"], "to": p["to"], "size": THREAT_FETCH_SIZE}),
    Endpoint("engine-status", "/api/admin/dashboard/engine-status", lambda p: {}, uses_period=False),
    Endpoint("daily-blocks", "/api/admin/dashboard/daily-blocks",
             lambda p: {"from": p["from"], "to": p["to"], "tz": TZ, "stackBy": "SOURCE"}),
    Endpoint("top-sources-agent", "/api/admin/dashboard/top-sources",
             lambda p: {"from": p["from"], "to": p["to"], "by": "AGENT", "size": TOP_SOURCES_SIZE}),
    Endpoint("top-sources-host", "/api/admin/dashboard/top-sources",
             lambda p: {"from": p["from"], "to": p["to"], "by": "HOST", "size": TOP_SOURCES_SIZE}),
    Endpoint("download-events", "/api/admin/download-events",
             lambda p: {"decision": ["BLOCKED", "FAIL_CLOSE"], "from": p["from"], "to": p["to"],
                        "size": RECENT_BLOCKS_SIZE}),
]


@dataclass(frozen=True)
class Sample:
    scenario: str
    endpoint: str
    period_days: int  # 기간을 안 쓰는 엔드포인트는 0
    admins: int
    ms: float
    status: int  # HTTP 상태. 타임아웃·연결 오류는 0


@dataclass(frozen=True)
class RoundSample:
    admins: int  # 동시 관리자 수
    admin: int  # 관리자 번호(0부터)
    round: int  # 라운드 번호(1부터)
    ms: float  # 화면 전체 지연 = 가장 늦은 응답
    errors: int  # 이 라운드에서 실패한 요청 수


def is_error(status: int) -> bool:
    return not (200 <= status < 300)


def percentile(values: list[float], p: float) -> float:
    """최근접 순위 방식: 오름차순 n개 중 ceil(p*n)번째."""
    if not values:
        raise ValueError("값이 없다")
    ordered = sorted(values)
    rank = max(1, math.ceil(p * len(ordered)))
    return ordered[rank - 1]


def period_range(days: int, now: datetime | None = None) -> dict:
    """콘솔 periodRange와 같은 형식: ISO-8601 instant(밀리초, Z). [now-days, now)."""
    now = now or datetime.now(timezone.utc)

    def fmt(t: datetime) -> str:
        return t.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z"

    return {"from": fmt(now - timedelta(days=days)), "to": fmt(now)}


class Api:
    """관리자 한 명의 세션. 토큰이 만료돼 401이 오면 다시 로그인해 한 번 재시도한다."""

    def __init__(self, client: httpx.AsyncClient, username: str, password: str):
        self.client = client
        self.username = username
        self.password = password
        self.token: str | None = None
        self._login_lock = asyncio.Lock()

    @classmethod
    def create(cls, base: str, username: str, password: str) -> "Api":
        client = httpx.AsyncClient(base_url=base, timeout=BASELINE_TIMEOUT_S,  # 실제 상한은 get()의 asyncio.timeout이 건다
                                   limits=httpx.Limits(max_connections=32))
        return cls(client, username, password)

    async def aclose(self):
        await self.client.aclose()

    async def _login(self, stale: str | None):
        async with self._login_lock:
            if self.token != stale:  # 동시에 401을 받은 다른 요청이 이미 다시 로그인했다
                return
            r = await self.client.post("/api/admin/auth/login",
                                       json={"username": self.username, "password": self.password})
            if r.status_code != 200:
                raise RuntimeError(f"로그인 실패: HTTP {r.status_code}")
            self.token = r.json()["accessToken"]

    async def _send(self, ep: Endpoint, params: dict) -> httpx.Response:
        return await self.client.get(ep.path, params=params, headers={"Authorization": f"Bearer {self.token}"})

    async def ensure_login(self):
        if self.token is None:
            await self._login(None)

    async def get(self, ep: Endpoint, period_days: int, timeout_s: float | None = None) -> tuple[float, int]:
        """(걸린 ms, HTTP 상태). 요청 하나(재로그인·재시도 포함)에 총 timeout_s(기본 REQUEST_TIMEOUT_S) 상한을 건다.
        상한 초과는 상태 0, ms=상한(timeout_s*1000). 연결 오류는 상태 0, 재로그인 실패는 401. 401은 재로그인 후 재시도한 결과만 남긴다.
        첫 로그인 실패는 RuntimeError로 올려 보낸다."""
        limit = REQUEST_TIMEOUT_S if timeout_s is None else timeout_s
        params = ep.params(period_range(period_days))
        await self.ensure_login()
        start = time.perf_counter()
        try:
            async with asyncio.timeout(limit):
                for attempt in (0, 1):
                    used = self.token
                    start = time.perf_counter()
                    r = await self._send(ep, params)
                    if r.status_code == 401 and attempt == 0:
                        await self._login(used)
                        continue
                    return (time.perf_counter() - start) * 1000, r.status_code
        except TimeoutError:
            return limit * 1000, 0
        except httpx.TransportError:
            return (time.perf_counter() - start) * 1000, 0
        except RuntimeError:  # 재로그인 실패
            return (time.perf_counter() - start) * 1000, 401
        raise AssertionError("도달 불가")


async def run_baseline(client: Api, endpoints: list[Endpoint] = ENDPOINTS, periods=(1, 7, 30, 90),
                       warmup=3, n=30) -> list[Sample]:
    """엔드포인트·기간마다 워밍업 warmup회(기록 안 함) 뒤 n회를 순서대로 잰다. 기간을 안 쓰는 엔드포인트는 한 번만."""
    samples: list[Sample] = []
    for ep in endpoints:
        for days in (periods if ep.uses_period else (0,)):
            for _ in range(warmup):
                await client.get(ep, days, BASELINE_TIMEOUT_S)
            for _ in range(n):
                ms, status = await client.get(ep, days, BASELINE_TIMEOUT_S)
                samples.append(Sample("baseline", ep.name, days, 1, ms, status))
    return samples


async def _round(api: Api, period_days: int) -> tuple[float, int]:
    """화면 전체 요청을 동시에 보내고 (가장 늦은 응답 ms, 실패 수)를 돌려 준다.
    라운드 전체에 REQUEST_TIMEOUT_S 총 상한: 넘으면 끝나지 않은 요청은 상태 0으로 센다."""
    limit = REQUEST_TIMEOUT_S
    start = time.perf_counter()
    tasks = [asyncio.ensure_future(api.get(ep, period_days)) for ep in ENDPOINTS]
    done, pending = await asyncio.wait(tasks, timeout=limit)
    for t in pending:
        t.cancel()
    if pending:
        await asyncio.gather(*pending, return_exceptions=True)
    errors = len(pending)
    slowest = limit * 1000 if pending else 0.0
    for t in done:
        if t.exception() is not None:  # 예기치 못한 오류도 실패로 센다
            errors += 1
            slowest = max(slowest, (time.perf_counter() - start) * 1000)
            continue
        ms, status = t.result()
        slowest = max(slowest, ms)
        errors += is_error(status)
    return slowest, errors


async def _admin_loop(api: Api, admins: int, admin: int, rounds: int, interval_s: float,
                      period_days: int) -> list[RoundSample]:
    await api.ensure_login()  # 첫 로그인 실패는 여기서 RuntimeError로 바로 끝낸다
    out: list[RoundSample] = []
    for i in range(1, rounds + 1):
        ms, errors = await _round(api, period_days)
        out.append(RoundSample(admins, admin, i, ms, errors))
        if i < rounds:
            await asyncio.sleep(interval_s)  # 콘솔 usePolling: 응답 뒤에 간격만큼 쉬고 다음 요청
    return out


async def run_polling(client_factory: Callable[[], Api], admins: int, rounds: int = 24,
                      interval_s: float = POLL_INTERVAL_S, period_days: int = DEFAULT_PERIOD_DAYS) -> list[RoundSample]:
    """관리자 admins명이 각자 로그인해, 라운드(화면 전체 8개 요청을 동시에)가 끝난 뒤 interval_s를 쉬고 다음 라운드를 보낸다."""
    apis = [client_factory() for _ in range(admins)]
    try:
        results = await asyncio.gather(*(_admin_loop(a, admins, idx, rounds, interval_s, period_days)
                                         for idx, a in enumerate(apis)))
    finally:
        await asyncio.gather(*(a.aclose() for a in apis))
    return [r for per_admin in results for r in per_admin]


def _stats(endpoint, period_days, admins, values, errors) -> dict:
    return {"endpoint": endpoint, "period_days": period_days, "admins": admins, "n": len(values),
            "p50_ms": round(percentile(values, 0.5), 1), "p95_ms": round(percentile(values, 0.95), 1),
            "max_ms": round(max(values), 1), "errors": errors}


def summarize(samples: list[Sample], rounds: list[RoundSample]) -> list[dict]:
    groups: dict[tuple, list[Sample]] = {}
    for s in samples:
        groups.setdefault((s.endpoint, s.period_days, s.admins), []).append(s)
    rows = [_stats(k[0], k[1], k[2], [s.ms for s in v], sum(1 for s in v if is_error(s.status)))
            for k, v in groups.items()]
    by_admins: dict[int, list[RoundSample]] = {}
    for r in rounds:
        by_admins.setdefault(r.admins, []).append(r)
    rows += [_stats("screen", DEFAULT_PERIOD_DAYS, a, [r.ms for r in v], sum(1 for r in v if r.errors))
             for a, v in by_admins.items()]
    return rows


def _write(path: Path, fields: list[str], rows: list[dict]):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)


def write_summary(path: Path, rows: list[dict]):
    """같은 (endpoint, period_days, admins) 키는 덮어쓰고 나머지는 남겨, 기준선·폴링 결과가 한 파일에 쌓이게 한다."""
    path = Path(path)
    merged: dict[tuple, dict] = {}
    if path.exists():
        for old in csv.DictReader(path.open()):
            merged[(old["endpoint"], int(old["period_days"]), int(old["admins"]))] = old
    for r in rows:
        merged[(r["endpoint"], int(r["period_days"]), int(r["admins"]))] = r
    _write(path, SUMMARY_FIELDS, list(merged.values()))


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="위협 대시보드 응답 시간 측정")
    ap.add_argument("--base", required=True, help="예: http://nginx")
    ap.add_argument("--stage", required=True, help="결과 파일 접두어. 예: pcs-10000")
    ap.add_argument("--scenario", required=True, choices=["baseline", "polling"])
    ap.add_argument("--admins", type=int, default=1, choices=[1, 5], help="polling 동시 관리자 수")
    ap.add_argument("--rounds", type=int, default=24)
    ap.add_argument("--interval", type=float, default=POLL_INTERVAL_S)
    ap.add_argument("--warmup", type=int, default=3)
    ap.add_argument("-n", type=int, default=30, help="baseline 측정 횟수")
    ap.add_argument("--periods", default="1,7,30,90", help="baseline 기간(일), 쉼표 구분. 빠른 확인은 7")
    ap.add_argument("--out", default="out")
    ap.add_argument("--username", default=os.environ.get("ADMIN_BOOTSTRAP_USERNAME"))
    ap.add_argument("--password", default=os.environ.get("ADMIN_BOOTSTRAP_PASSWORD"))
    args = ap.parse_args(argv)
    try:
        args.periods = tuple(int(x) for x in args.periods.split(","))
    except ValueError:
        ap.error("--periods는 정수 쉼표 목록이다: 예 1,7,30,90")
    if not args.username or not args.password:
        ap.error("관리자 계정이 없다: --username/--password 또는 ADMIN_BOOTSTRAP_USERNAME/_PASSWORD")
    return args


async def amain(args) -> int:
    out = Path(args.out)
    factory = lambda: Api.create(args.base, args.username, args.password)  # noqa: E731
    samples: list[Sample] = []
    rounds: list[RoundSample] = []
    if args.scenario == "baseline":
        api = factory()
        try:
            samples = await run_baseline(api, periods=args.periods, warmup=args.warmup, n=args.n)
        finally:
            await api.aclose()
        _write(out / f"{args.stage}-baseline.csv", SAMPLE_FIELDS,
               [{k: getattr(s, k) for k in SAMPLE_FIELDS} for s in samples])
    else:
        rounds = await run_polling(factory, args.admins, args.rounds, args.interval)
        _write(out / f"{args.stage}-polling.csv", ROUND_FIELDS,
               [{"scenario": "polling", "endpoint": "screen", "period_days": DEFAULT_PERIOD_DAYS, **vars(r)}
                for r in rounds])
    rows = summarize(samples, rounds)
    write_summary(out / f"{args.stage}-summary.csv", rows)
    for r in rows:
        print(r)
    return 0


def main(argv=None) -> int:
    return asyncio.run(amain(parse_args(argv)))


if __name__ == "__main__":
    raise SystemExit(main())
