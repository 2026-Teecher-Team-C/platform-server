import asyncio
import json
import threading
from concurrent import futures
from http.server import BaseHTTPRequestHandler, HTTPServer

import grpc
import pytest

from quality import client
from quality.records import Item
from teecher.agent.v1 import agent_pb2, agent_pb2_grpc
from teecher.verdict.v1 import verdict_pb2, verdict_pb2_grpc

TLSH = "T1" + "B" * 70
AGENT_TOKEN = "agent-secret-token-123"


def item(prefix, n):
    sha = (prefix + format(n, "x")).ljust(64, "0")
    return Item(item_id=f"X-{n}", set="M", sha256=sha, tlsh=TLSH, file_size=1000 + n, family="", file_type="",
                variant="", origin_sha256="")


class FakeVerdict(verdict_pb2_grpc.VerdictServiceServicer):
    def __init__(self):
        self.requests = []
        self.reports = []

    def ReportEvent(self, request, context):
        self.reports.append((request, dict(context.invocation_metadata())))
        return verdict_pb2.ReportEventResponse()

    def CheckHash(self, request, context):
        self.requests.append((request, dict(context.invocation_metadata())))
        if request.sha256.startswith("bb"):
            context.abort(grpc.StatusCode.UNAVAILABLE, "engine down")
        if request.sha256.startswith("aa"):
            return verdict_pb2.CheckHashResponse(decision=verdict_pb2.DECISION_BLOCK,
                                                 source=verdict_pb2.DECISION_SOURCE_SIMILARITY, reason="tlsh 12")
        return verdict_pb2.CheckHashResponse(decision=verdict_pb2.DECISION_UNKNOWN)


class FakeAgent(agent_pb2_grpc.AgentServiceServicer):
    def RegisterAgent(self, request, context):
        assert request.enrollment_token == "enroll-1"
        return agent_pb2.RegisterAgentResponse(agent_id="agent-uuid-1", agent_token=AGENT_TOKEN)


@pytest.fixture
def grpc_server():
    verdict = FakeVerdict()
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
    verdict_pb2_grpc.add_VerdictServiceServicer_to_server(verdict, server)
    agent_pb2_grpc.add_AgentServiceServicer_to_server(FakeAgent(), server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    yield f"127.0.0.1:{port}", verdict
    server.stop(0)


class FakeRest(BaseHTTPRequestHandler):
    calls = []

    def log_message(self, *args):
        pass

    def _send(self, status, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n)) if n else None

    def do_POST(self):
        body = self._body()
        FakeRest.calls.append(("POST", self.path, body, self.headers.get("Authorization")))
        if self.path == "/api/admin/auth/login":
            return self._send(200, {"accessToken": "admin-access", "refreshToken": "r", "tokenType": "Bearer"})
        if self.path == "/api/admin/agents/enrollment-tokens":
            return self._send(201, {"token": "enroll-1"})
        if self.path == "/api/admin/blacklist/import":
            return self._send(200, {"received": len(body["entries"]), "added": len(body["entries"])})
        self._send(404)

    def do_GET(self):
        FakeRest.calls.append(("GET", self.path, None, self.headers.get("Authorization")))
        self._send(200, {"blockDistance": 30, "warnDistance": 70,
                         "limits": {"blockMin": 0, "blockMax": 50, "warnMax": 70}})

    def do_PUT(self):
        FakeRest.calls.append(("PUT", self.path, self._body(), self.headers.get("Authorization")))
        self._send(200, {})

    def do_DELETE(self):
        FakeRest.calls.append(("DELETE", self.path, None, self.headers.get("Authorization")))
        self._send(500 if self.path.endswith("f" * 64) else 204)


@pytest.fixture
def rest():
    FakeRest.calls = []
    server = HTTPServer(("127.0.0.1", 0), FakeRest)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_port}"
    server.shutdown()


def test_운영_가드():
    with pytest.raises(SystemExit) as raised:
        client.check_target("https://example.com", "example.com:443", prod_flag=False)
    assert raised.value.code == 2
    client.check_target("http://localhost:8080", "localhost:9090", prod_flag=False)
    client.check_target("http://nginx:80", "nginx:80", prod_flag=False)
    client.check_target("https://example.com", "example.com:443", prod_flag=True)


def test_run_checks(grpc_server, capsys):
    target, verdict = grpc_server
    items = [item("aa", 1), item("bb", 2), item("cc", 3)]

    responses = asyncio.run(client.run_checks(target, False, AGENT_TOKEN, items, "p1", concurrency=2, timeout=5))

    by_id = {r.item_id: r for r in responses}
    assert (by_id["X-1"].decision, by_id["X-1"].source, by_id["X-1"].reason) == (
        "DECISION_BLOCK", "DECISION_SOURCE_SIMILARITY", "tlsh 12")
    assert by_id["X-2"].error == "UNAVAILABLE"
    assert by_id["X-3"].decision == "DECISION_UNKNOWN" and by_id["X-3"].error == ""
    assert all(r.pass_id == "p1" for r in responses)
    assert len({r.event_id for r in responses}) == 3
    sent = {req.sha256: (req, meta) for req, meta in verdict.requests}
    for it in items:
        req, meta = sent[it.sha256]
        assert (req.tlsh, req.file_size) == (it.tlsh, it.file_size)
        assert meta["authorization"] == "Bearer " + AGENT_TOKEN
    assert {r.event_id for r in responses} == {req.event_id for req, _ in verdict.requests}
    assert AGENT_TOKEN not in capsys.readouterr().out


def test_run_checks는_응답마다_콜백을_부른다(grpc_server):
    target, _ = grpc_server
    seen = []

    responses = asyncio.run(client.run_checks(target, False, AGENT_TOKEN, [item("cc", 1), item("cc", 2)], "p1",
                                              on_result=seen.append))

    assert sorted(r.item_id for r in seen) == sorted(r.item_id for r in responses) == ["X-1", "X-2"]


def test_TLSH로_차단된_항목만_다운로드_이벤트로_보고한다(grpc_server):
    target, verdict = grpc_server
    blocked, unknown = item("aa", 1), item("cc", 2)
    blocked = blocked.__class__(**{**blocked.__dict__, "family": "Mirai"})

    responses = asyncio.run(client.run_checks(target, False, AGENT_TOKEN, [blocked, unknown], "p1",
                                              report_blocked=True))

    assert len(verdict.reports) == 1
    report, meta = verdict.reports[0]
    sent = next(r for r in responses if r.item_id == blocked.item_id)
    assert report.event_id == sent.event_id
    assert report.sha256 == blocked.sha256
    assert report.decision == verdict_pb2.FINAL_DECISION_BLOCKED
    assert report.decision_source == verdict_pb2.DECISION_SOURCE_SIMILARITY
    assert report.request_host == "quality-test.local"
    assert "Mirai" in report.filename and blocked.item_id in report.filename
    assert report.file_size == blocked.file_size
    assert meta["authorization"] == "Bearer " + AGENT_TOKEN


def test_보고를_끄면_ReportEvent를_보내지_않는다(grpc_server):
    target, verdict = grpc_server

    asyncio.run(client.run_checks(target, False, AGENT_TOKEN, [item("aa", 1)], "p1"))

    assert verdict.reports == []


def test_관리_api(rest, grpc_server):
    target, _ = grpc_server
    admin = client.Admin(rest)
    admin.login("admin", "pw-secret")

    assert admin.get_policy() == (30, 70)
    admin.put_policy(25, 60)
    agent_id, token = client.register_agent(target, admin.enrollment_token(), tls=False)

    assert (agent_id, token) == ("agent-uuid-1", AGENT_TOKEN)
    put = next(c for c in FakeRest.calls if c[0] == "PUT")
    assert put[1] == "/api/admin/similarity-policy"
    assert put[2] == {"blockDistance": 25, "warnDistance": 60}
    assert all(c[3] == "Bearer admin-access" for c in FakeRest.calls if c[1] != "/api/admin/auth/login")


def test_변종_원본_등록은_1000건씩(rest):
    admin = client.Admin(rest)
    admin.login("admin", "pw")
    items = [item("dd", n) for n in range(1500)]

    result = admin.import_blacklist(items, label="quality-test-r1")

    imports = [c for c in FakeRest.calls if c[1] == "/api/admin/blacklist/import"]
    assert [len(c[2]["entries"]) for c in imports] == [1000, 500]
    assert imports[0][2]["entries"][0] == {"sha256": items[0].sha256, "tlsh": TLSH, "label": "quality-test-r1"}
    assert result["added"] == 1500


def test_해제_실패_보고(rest):
    admin = client.Admin(rest)
    admin.login("admin", "pw")
    shas = ["1" * 64, "f" * 64, "2" * 64]

    released, failed = admin.release_blacklist(shas)

    assert (released, failed) == (2, ["f" * 64])
