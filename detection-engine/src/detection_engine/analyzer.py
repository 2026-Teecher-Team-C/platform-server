import time
from dataclasses import dataclass, field
from pathlib import Path

import yara

ENGINE_VERSION = "0.1.0"
MAX_MATCHED_STRINGS = 20


@dataclass(frozen=True)
class RuleHit:
    rule_name: str
    severity: str
    matched_strings: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class Analysis:
    malicious: bool
    matches: list[RuleHit]
    duration_ms: int


class Analyzer:
    """YARA 매칭만 수행한다. 매직바이트·PE·엔트로피와 워커 프로세스 격리는 이후 단계에서 붙는다."""

    def __init__(self, rules_dir: Path):
        rule_files = {path.stem: str(path) for path in sorted(rules_dir.glob("*.yar"))}
        if not rule_files:
            raise ValueError(f"YARA 룰이 없습니다: {rules_dir}")
        self._rules = yara.compile(filepaths=rule_files)

    def analyze(self, data: bytes) -> Analysis:
        started = time.monotonic()
        hits = [
            RuleHit(
                rule_name=match.rule,
                severity=str(match.meta.get("severity", "MEDIUM")),
                matched_strings=[string.identifier for string in match.strings][:MAX_MATCHED_STRINGS],
            )
            for match in self._rules.match(data=data)
        ]
        duration_ms = int((time.monotonic() - started) * 1000)
        return Analysis(malicious=bool(hits), matches=hits, duration_ms=duration_ms)
