"""측정 클라이언트 단위 시험. httpx.MockTransport로 서버를 흉내 내며 네트워크·DB는 쓰지 않는다."""
import asyncio
import csv
import functools
import time

import httpx
import pytest

import bench


def sync(fn):
    """pytest-asyncio 없이 코루틴 시험을 돌린다(이미지에 플러그인이 없다)."""
    @functools.wraps(fn)  # 픽스처(monkeypatch 등) 인자가 보이도록 시그니처를 유지한다
    def wrapper(*args, **kwargs):
        asyncio.run(fn(*args, **kwargs))
    return wrapper


class FakeServer:
    """로그인과 대시보드 경로를 흉내 낸다. delays는 경로별 지연(초), expire_first는 첫 데이터 요청에 401."""

    def __init__(self, delays=None, expire_first=False, status=None, raise_exc=None):
        self.delays = delays or {}
        self.expire_first = expire_first
        self.status = status or {}
        self.raise_exc = raise_exc
        self.logins = 0
        self.requests = []
        self.spans = []
        self.inflight = 0
        self.max_inflight = 0
        self._expired = False

    async def handler(self, request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/admin/auth/login":
            self.logins += 1
            return httpx.Response(200, json={"accessToken": f"tok{self.logins}", "refreshToken": "r", "tokenType": "Bearer"})
        self.requests.append(request)
        if self.expire_first and not self._expired:
            self._expired = True
            return httpx.Response(401, json={})
        if self.raise_exc:
            raise self.raise_exc
        self.inflight += 1
        self.max_inflight = max(self.max_inflight, self.inflight)
        began = time.monotonic()
        try:
            await asyncio.sleep(self.delays.get(request.url.path, 0))
        finally:
            self.inflight -= 1
            self.spans.append((began, time.monotonic()))
        return httpx.Response(self.status.get(request.url.path, 200), json={})


def make_api(server):
    client = httpx.AsyncClient(transport=httpx.MockTransport(server.handler), base_url="http://t")
    return bench.Api(client, "admin", "pw")


def test_percentile():
    assert bench.percentile([10, 20, 30, 40], 0.95) == 40
    assert bench.percentile([10, 20, 30, 40], 0.5) == 20
    assert bench.percentile([40, 10, 30, 20], 0.5) == 20


def test_엔드포인트는_콘솔이_보내는_경로와_파라미터를_쓴다():
    by_name = {e.name: e for e in bench.ENDPOINTS}
    assert len(bench.ENDPOINTS) == 8
    p = {"from": "F", "to": "T"}
    assert by_name["summary"].path == "/api/admin/dashboard/summary" and by_name["summary"].params(p) == p
    assert by_name["threats"].params(p) == {**p, "size": 50}
    assert by_name["daily-blocks"].params(p) == {**p, "tz": "Asia/Seoul", "stackBy": "SOURCE"}
    assert by_name["top-sources-agent"].params(p) == {**p, "by": "AGENT", "size": 5}
    assert by_name["top-sources-host"].params(p) == {**p, "by": "HOST", "size": 5}
    assert by_name["unacknowledged-blocks"].params(p) == {"size": 5}
    assert by_name["engine-status"].params(p) == {}
    ev = by_name["download-events"]
    assert ev.path == "/api/admin/download-events"
    assert ev.params(p) == {"decision": ["BLOCKED", "FAIL_CLOSE"], **p, "size": 10}


def test_타임아웃은_콘솔과_같은_8초():
    assert bench.REQUEST_TIMEOUT_S == 8.0 and bench.POLL_INTERVAL_S == 5.0
    assert bench.BASELINE_TIMEOUT_S == 120.0


def test_cli_기간_목록(monkeypatch):
    monkeypatch.setenv("ADMIN_BOOTSTRAP_USERNAME", "u")
    monkeypatch.setenv("ADMIN_BOOTSTRAP_PASSWORD", "p")
    base = ["--base", "http://nginx", "--stage", "s", "--scenario", "baseline"]
    assert bench.parse_args(base).periods == (1, 7, 30, 90)
    assert bench.parse_args(base + ["--periods", "7"]).periods == (7,)
    with pytest.raises(SystemExit):
        bench.parse_args(base + ["--periods", "a,b"])


@sync
async def test_401이면_다시_로그인():
    server = FakeServer(expire_first=True)
    api = make_api(server)
    ms, status = await api.get(bench.ENDPOINTS[0], 7)
    assert status == 200 and server.logins == 2 and len(server.requests) == 2
    assert server.requests[1].headers["Authorization"] == "Bearer tok2"
    samples = await bench.run_baseline(api, [bench.ENDPOINTS[0]], periods=(7,), warmup=0, n=3)
    assert [s.status for s in samples] == [200] * 3


@sync
async def test_5xx와_타임아웃은_오류로_센다():
    api = make_api(FakeServer(status={"/api/admin/dashboard/summary": 503}))
    s = await bench.run_baseline(api, [bench.ENDPOINTS[0]], periods=(7,), warmup=0, n=2)
    assert [x.status for x in s] == [503, 503] and all(bench.is_error(x.status) for x in s)
    api = make_api(FakeServer(raise_exc=httpx.ReadTimeout("slow")))
    s = await bench.run_baseline(api, [bench.ENDPOINTS[0]], periods=(7,), warmup=0, n=2)
    assert [x.status for x in s] == [0, 0] and all(bench.is_error(x.status) for x in s)


@sync
async def test_기준선은_워밍업을_빼고_n개():
    server = FakeServer()
    api = make_api(server)
    eps = [bench.ENDPOINTS[0], bench.ENDPOINTS[3]]  # 기간 있는 것, 기간 없는 것(engine-status)
    samples = await bench.run_baseline(api, eps, periods=(1, 7), warmup=3, n=5)
    assert len(samples) == 5 * 2 + 5  # 기간 있는 건 기간마다, 없는 건 한 번
    assert len(server.requests) == (3 + 5) * 3
    assert {s.period_days for s in samples if s.endpoint == "engine-status"} == {0}
    assert all(s.scenario == "baseline" and s.admins == 1 for s in samples)


@sync
async def test_기준선은_기간_일수만큼_from을_당긴다():
    server = FakeServer()
    api = make_api(server)
    await bench.run_baseline(api, [bench.ENDPOINTS[0]], periods=(30,), warmup=0, n=1)
    q = server.requests[0].url.params
    from datetime import datetime
    f, t = (datetime.fromisoformat(q[k].replace("Z", "+00:00")) for k in ("from", "to"))
    assert (t - f).days == 30 and q["from"].endswith("Z")


@sync
async def test_폴링_라운드_지연은_가장_늦은_응답():
    delays = {"/api/admin/dashboard/summary": 0.01, "/api/admin/dashboard/threats": 0.05,
              "/api/admin/dashboard/daily-blocks": 0.03}
    server = FakeServer(delays=delays)
    rounds = await bench.run_polling(lambda: make_api(server), admins=1, rounds=2, interval_s=0.0)
    assert len(rounds) == 2
    assert all(0.05 * 1000 <= r.ms < 0.05 * 1000 + 40 for r in rounds)
    assert all(r.errors == 0 and r.admins == 1 for r in rounds)
    assert len(server.requests) == 2 * 8


@sync
async def test_폴링_관리자별로_따로_로그인하고_라운드가_나뉜다():
    server = FakeServer()
    rounds = await bench.run_polling(lambda: make_api(server), admins=3, rounds=2, interval_s=0.0)
    assert len(rounds) == 6 and server.logins == 3
    assert sorted({r.admin for r in rounds}) == [0, 1, 2]


@sync
async def test_느린_라운드는_겹치지_않음():
    # 라운드 지연(0.05s)이 간격(0.01s)보다 길다. 한 관리자의 동시 요청은 한 라운드분(8개)을 넘지 않는다.
    server = FakeServer(delays={"/api/admin/dashboard/threats": 0.05})
    rounds = await bench.run_polling(lambda: make_api(server), admins=1, rounds=3, interval_s=0.01)
    assert server.max_inflight <= 8
    assert len(rounds) == 3 and all(r.ms >= 50 for r in rounds)


@sync
async def test_폴링_오류는_라운드_오류로_센다():
    server = FakeServer(status={"/api/admin/dashboard/engine-status": 500})
    rounds = await bench.run_polling(lambda: make_api(server), admins=1, rounds=1, interval_s=0.0)
    assert rounds[0].errors == 1


def test_요약은_p50_p95_max_오류를_계산하고_병합한다(tmp_path):
    base = [bench.Sample("baseline", "summary", 30, 1, ms, 200) for ms in (10, 20, 30, 40)]
    base.append(bench.Sample("baseline", "summary", 30, 1, 500, 503))
    rows = bench.summarize(base, [])
    r = rows[0]
    assert (r["endpoint"], r["period_days"], r["admins"], r["n"], r["errors"]) == ("summary", 30, 1, 5, 1)
    assert r["p50_ms"] == 30 and r["p95_ms"] == 500 and r["max_ms"] == 500
    poll = bench.summarize([], [bench.RoundSample(5, 0, i, float(i), 0) for i in range(1, 5)])
    assert poll[0]["endpoint"] == "screen" and poll[0]["admins"] == 5 and poll[0]["period_days"] == 7
    path = tmp_path / "x-summary.csv"
    bench.write_summary(path, rows)
    bench.write_summary(path, poll)
    got = list(csv.DictReader(path.open()))
    assert {g["endpoint"] for g in got} == {"summary", "screen"}
    bench.write_summary(path, rows)  # 같은 키는 덮어쓴다
    assert len(list(csv.DictReader(path.open()))) == 2


def test_cli_인자(monkeypatch):
    monkeypatch.setenv("ADMIN_BOOTSTRAP_USERNAME", "u")
    monkeypatch.setenv("ADMIN_BOOTSTRAP_PASSWORD", "p")
    a = bench.parse_args(["--base", "http://nginx", "--stage", "pcs-10000", "--scenario", "polling", "--admins", "5"])
    assert (a.base, a.stage, a.scenario, a.admins, a.username, a.password) == ("http://nginx", "pcs-10000", "polling", 5, "u", "p")
    with pytest.raises(SystemExit):
        bench.parse_args(["--base", "x", "--stage", "s", "--scenario", "polling", "--admins", "2"])


@sync
async def test_라운드가_끝난_뒤_간격만큼_쉬고_다음_라운드를_시작():
    # 고정 간격(시작 기준)이면 간격이 0.05-0.02=0.03초라 실패한다. 콘솔처럼 종료 기준이면 0.05초 이상.
    server = FakeServer(delays={"/api/admin/dashboard/threats": 0.02})
    await bench.run_polling(lambda: make_api(server), admins=1, rounds=3, interval_s=0.05)
    spans = server.spans
    n = len(bench.ENDPOINTS)
    assert len(spans) == 3 * n
    for k in range(2):
        end = max(e for _, e in spans[k * n:(k + 1) * n])
        nxt = min(b for b, _ in spans[(k + 1) * n:(k + 2) * n])
        assert nxt - end >= 0.045


@sync
async def test_기준선_요청은_총_상한을_넘으면_오류(monkeypatch):
    monkeypatch.setattr(bench, "REQUEST_TIMEOUT_S", 0.05)
    server = FakeServer(delays={"/api/admin/dashboard/summary": 0.5})
    t0 = time.monotonic()
    ms, status = await make_api(server).get(bench.ENDPOINTS[0], 7)
    assert status == 0 and 40 <= ms < 200 and time.monotonic() - t0 < 0.3


@sync
async def test_기준선은_별도_상한을_쓰고_넘으면_상한값으로_오류_기록하고_요약에_나온다(monkeypatch):
    monkeypatch.setattr(bench, "BASELINE_TIMEOUT_S", 0.2)  # 폴링 상한(8초)과 별개
    server = FakeServer(delays={"/api/admin/dashboard/summary": 0.1})  # 폴링 상한이면 통과, 기준선 상한도 통과
    s = await bench.run_baseline(make_api(server), [bench.ENDPOINTS[0]], periods=(7,), warmup=0, n=2)
    assert [x.status for x in s] == [200, 200]  # 0.1초 지연은 REQUEST_TIMEOUT_S 와 무관하게 통과
    monkeypatch.setattr(bench, "REQUEST_TIMEOUT_S", 0.05)  # 폴링 상한을 줄여도 기준선은 영향받지 않는다
    s = await bench.run_baseline(make_api(server), [bench.ENDPOINTS[0]], periods=(7,), warmup=0, n=2)
    assert [x.status for x in s] == [200, 200]
    slow = FakeServer(delays={"/api/admin/dashboard/summary": 1.0})
    s = await bench.run_baseline(make_api(slow), [bench.ENDPOINTS[0]], periods=(7,), warmup=0, n=1)
    assert s[0].status == 0 and s[0].ms == 200.0  # ms = 상한 x 1000
    row = [r for r in bench.summarize(s, []) if r["endpoint"] == "summary"][0]
    assert row["errors"] == 1 and row["max_ms"] == 200.0


@sync
async def test_폴링_라운드는_전체에_총_상한을_건다(monkeypatch):
    monkeypatch.setattr(bench, "REQUEST_TIMEOUT_S", 0.1)
    server = FakeServer(delays={"/api/admin/dashboard/threats": 0.6, "/api/admin/dashboard/summary": 0.03})
    t0 = time.monotonic()
    rounds = await bench.run_polling(lambda: make_api(server), admins=1, rounds=1, interval_s=0.0)
    assert time.monotonic() - t0 < 0.4
    assert rounds[0].errors == 1 and 100 <= rounds[0].ms < 200


@sync
async def test_첫_로그인_실패는_명확히_종료하고_재로그인_실패는_오류로_센다():
    async def deny(request):
        return httpx.Response(401, json={})
    bad = bench.Api(httpx.AsyncClient(transport=httpx.MockTransport(deny), base_url="http://t"), "a", "b")
    with pytest.raises(RuntimeError, match="로그인 실패"):
        await bench.run_polling(lambda: bad, admins=1, rounds=1, interval_s=0.0)

    state = {"logins": 0}

    async def flaky(request):
        if request.url.path == "/api/admin/auth/login":
            state["logins"] += 1
            if state["logins"] > 1:
                return httpx.Response(401, json={})
            return httpx.Response(200, json={"accessToken": "t"})
        return httpx.Response(401, json={})  # 토큰 만료 상태 고정
    api = bench.Api(httpx.AsyncClient(transport=httpx.MockTransport(flaky), base_url="http://t"), "a", "b")
    rounds = await bench.run_polling(lambda: api, admins=1, rounds=2, interval_s=0.0)
    assert len(rounds) == 2 and all(r.errors == len(bench.ENDPOINTS) for r in rounds)
