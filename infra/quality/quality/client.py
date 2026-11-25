"""서버 호출: 관리 REST(로그인·정책·등록 토큰·블랙리스트)와 gRPC(RegisterAgent·CheckHash).

토큰과 비밀번호는 객체·지역 변수에만 둔다. 출력·로그·파일에 남기지 않는다.
"""
from __future__ import annotations

import asyncio
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

import grpc

from quality.records import Item, Response
from teecher.agent.v1 import agent_pb2, agent_pb2_grpc
from teecher.verdict.v1 import verdict_pb2, verdict_pb2_grpc

LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1", "nginx", "api-server", "host.docker.internal"}
IMPORT_BATCH = 1000


def _host(target: str) -> str:
    if "://" in target:
        return urllib.parse.urlsplit(target).hostname or ""
    return target.rsplit(":", 1)[0].strip("[]")


def check_target(http_url: str, grpc_target: str, prod_flag: bool) -> None:
    """localhost·compose 서비스가 아니면 운영으로 보고, --i-know-this-is-prod 없이는 멈춘다."""
    remote = [t for t in (http_url, grpc_target) if _host(t) not in LOCAL_HOSTS]
    if remote and not prod_flag:
        print(f"대상이 로컬이 아닙니다({', '.join(remote)}). 운영에 보내려면 --i-know-this-is-prod 를 붙이세요.",
              file=sys.stderr)
        raise SystemExit(2)


class Admin:
    def __init__(self, http_url: str):
        self._base = http_url.rstrip("/")
        self._access: str | None = None

    def _call(self, method: str, path: str, body=None, expect=(200,)):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self._base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if self._access:
            req.add_header("Authorization", "Bearer " + self._access)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                status, raw = resp.status, resp.read()
        except urllib.error.HTTPError as err:
            status, raw = err.code, err.read()
        if status not in expect:
            raise RuntimeError(f"{method} {path} → {status}")
        return json.loads(raw) if raw else None

    def login(self, user: str, password: str) -> None:
        body = self._call("POST", "/api/admin/auth/login", {"username": user, "password": password})
        self._access = body["accessToken"]

    def get_policy(self) -> tuple[int, int]:
        body = self._call("GET", "/api/admin/similarity-policy")
        return body["blockDistance"], body["warnDistance"]

    def put_policy(self, block: int, warn: int) -> None:
        self._call("PUT", "/api/admin/similarity-policy", {"blockDistance": block, "warnDistance": warn})

    def enrollment_token(self) -> str:
        return self._call("POST", "/api/admin/agents/enrollment-tokens", {"expiresInMinutes": 10},
                          expect=(200, 201))["token"]

    def import_blacklist(self, items: list[Item], label: str) -> dict:
        totals: dict = {}
        for start in range(0, len(items), IMPORT_BATCH):
            batch = items[start:start + IMPORT_BATCH]
            body = self._call("POST", "/api/admin/blacklist/import", {
                "source": "quality-test",
                "entries": [{"sha256": i.sha256, "tlsh": i.tlsh, "label": label} for i in batch]})
            for key, value in body.items():
                if isinstance(value, int):
                    totals[key] = totals.get(key, 0) + value
        return totals

    def release_blacklist(self, shas: list[str]) -> tuple[int, list[str]]:
        released, failed = 0, []
        for sha in shas:
            try:
                self._call("DELETE", f"/api/admin/blacklist/{sha}", expect=(204,))
                released += 1
            except RuntimeError:
                failed.append(sha)
        return released, failed


def _channel(target: str, tls: bool, aio: bool = False):
    module = grpc.aio if aio else grpc
    options = [("grpc.max_receive_message_length", 8 * 1024 * 1024)]
    if tls:
        return module.secure_channel(target, grpc.ssl_channel_credentials(), options=options)
    return module.insecure_channel(target, options=options)


def register_agent(grpc_target: str, enrollment_token: str, tls: bool) -> tuple[str, str]:
    with _channel(grpc_target, tls) as ch:
        resp = agent_pb2_grpc.AgentServiceStub(ch).RegisterAgent(agent_pb2.RegisterAgentRequest(
            enrollment_token=enrollment_token,
            hostname="quality-" + uuid.uuid4().hex[:8],
            os_platform=agent_pb2.OS_PLATFORM_LINUX,
            agent_version="quality-harness",
            hardware_uuid=str(uuid.uuid4()),
        ), timeout=30)
    return resp.agent_id, resp.agent_token


async def run_checks(grpc_target: str, tls: bool, agent_token: str, items: list[Item], pass_id: str,
                     concurrency: int = 4, timeout: float = 30) -> list[Response]:
    meta = (("authorization", "Bearer " + agent_token),)
    gate = asyncio.Semaphore(concurrency)
    results: list[Response] = []

    async with _channel(grpc_target, tls, aio=True) as ch:
        stub = verdict_pb2_grpc.VerdictServiceStub(ch)

        async def one(it: Item) -> None:
            event_id = str(uuid.uuid4())
            async with gate:
                try:
                    resp = await stub.CheckHash(verdict_pb2.CheckHashRequest(
                        sha256=it.sha256, file_size=it.file_size, tlsh=it.tlsh, event_id=event_id),
                        timeout=timeout, metadata=meta)
                    results.append(Response(it.item_id, event_id, verdict_pb2.Decision.Name(resp.decision),
                                            verdict_pb2.DecisionSource.Name(resp.source), resp.reason, "", pass_id))
                except grpc.aio.AioRpcError as err:
                    results.append(Response(it.item_id, event_id, "", "", "", err.code().name, pass_id))

        await asyncio.gather(*(one(it) for it in items))
    return results


__all__ = ["Admin", "check_target", "register_agent", "run_checks"]
