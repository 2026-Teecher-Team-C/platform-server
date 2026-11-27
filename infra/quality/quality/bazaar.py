"""MalwareBazaar 샘플 받기와 암호 zip 메모리 읽기. 악성 zip은 디스크에 풀지 않는다."""
from __future__ import annotations

import json
import os
import tempfile
import zipfile
import zlib
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import pyzipper

from quality.yarascan import MAX_FILE_BYTES

API = "https://mb-api.abuse.ch/api/v1/"
_MISSING = frozenset({"file_not_found", "illegal_hash", "no_hash_provided"})
_TIMEOUT_S = 120


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """리다이렉트를 따라가지 않는다. Auth-Key 헤더가 다른 호스트로 새는 것을 막는다."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


class ZipUnreadable(Exception):
    """손상·빈 zip, 틀린 비밀번호 등 읽을 수 없는 zip. 호출부는 status error로 기록한다."""


def _default_post(url: str, data: dict, headers: dict) -> tuple[int, str, bytes]:
    req = urllib.request.Request(url, data=urllib.parse.urlencode(data).encode(), headers=headers, method="POST")
    try:
        with _OPENER.open(req, timeout=_TIMEOUT_S) as resp:
            return resp.status, resp.headers.get("Content-Type", ""), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", "") if e.headers else "", b""


def fetch_sample(sha256: str, auth_key: str, dest: Path, post=None) -> str:
    """ok(zip 저장) | missing(없는 해시) | error. 오류 정보에 키를 담지 않는다."""
    post = post or _default_post
    try:
        code, ctype, body = post(API, {"query": "get_file", "sha256_hash": sha256}, {"Auth-Key": auth_key})
    except Exception:
        return "error"
    if code != 200:
        return "error"
    if "zip" in ctype.lower():
        _write_atomic(Path(dest) / f"{sha256}.zip", body)
        return "ok"
    try:
        query_status = json.loads(body).get("query_status")
    except (ValueError, AttributeError):
        return "error"
    return "missing" if query_status in _MISSING else "error"


def _write_atomic(target: Path, body: bytes) -> None:
    fd, tmp = tempfile.mkstemp(dir=target.parent, prefix=".", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(body)
        os.replace(tmp, target)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def read_zip_sample(path: Path, password: bytes = b"infected", max_bytes: int = MAX_FILE_BYTES) -> bytes | None:
    """첫 항목만 메모리로 읽는다. 크기 초과면 None(헤더 선언 크기, 실제 읽기도 max_bytes+1까지만).

    읽을 수 없는 zip은 ZipUnreadable.
    """
    try:
        with pyzipper.AESZipFile(path) as z:
            z.setpassword(password)
            infos = z.infolist()
            if not infos:
                raise ZipUnreadable("empty zip")
            info = infos[0]
            if info.file_size > max_bytes:
                return None
            with z.open(info) as f:
                data = f.read(max_bytes + 1)
            return None if len(data) > max_bytes else data
    except ZipUnreadable:
        raise
    except (zipfile.BadZipFile, pyzipper.BadZipFile, RuntimeError, zlib.error, EOFError, NotImplementedError, OSError) as e:
        raise ZipUnreadable(type(e).__name__) from None
