"""운영 룰셋으로 바이트를 YARA 스캔한다. 심각도 규칙·컴파일 옵션은 detection-engine과 같다."""
from __future__ import annotations

import time

import yara

from quality.records import YaraResult

MAX_FILE_BYTES = 256 * 1024 * 1024
TIMEOUT_S = 60

_SEVERITIES = ("LOW", "MEDIUM", "HIGH", "CRITICAL")


def _severity(meta: dict) -> str:
    """detection_engine.analyzer._severity와 같은 규칙: 허용 문자열이면 대문자, 아니면 MEDIUM."""
    value = meta.get("severity")
    if isinstance(value, str) and value.upper() in _SEVERITIES:
        return value.upper()
    return "MEDIUM"


def compile_rules(source: str) -> yara.Rules:
    return yara.compile(source=source, includes=False)


def scan_bytes(
    rules: yara.Rules, data: bytes, disabled: frozenset[str], timeout_s: int = TIMEOUT_S
) -> tuple[str, list[tuple[str, str]], int]:
    started = time.monotonic()
    status = "ok"
    hits: dict[str, str] = {}
    try:
        for match in rules.match(data=data, timeout=timeout_s):
            if match.rule not in disabled:
                hits.setdefault(match.rule, _severity(match.meta))
    except yara.TimeoutError:
        status, hits = "timeout", {}
    except yara.Error:
        status, hits = "error", {}
    return status, sorted(hits.items()), int((time.monotonic() - started) * 1000)


def to_result(item_id: str, sha256: str, status: str, hits: list[tuple[str, str]], scan_ms: int) -> YaraResult:
    names = sorted({name for name, _ in hits})
    top = max((_SEVERITIES.index(sev) for _, sev in hits), default=None)
    return YaraResult(
        item_id=item_id,
        sha256=sha256,
        status=status,
        rule_count=len(names),
        rules=";".join(names),
        max_severity="" if top is None else _SEVERITIES[top],
        scan_ms=scan_ms,
    )
