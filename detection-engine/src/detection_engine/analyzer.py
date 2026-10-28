import time
from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path

import yara

ENGINE_VERSION = "0.1.0"
MAX_MATCHED_STRINGS = 20
# 업로드 룰 원문의 상한(바이트). 컴파일 비용과 메모리를 묶는다.
MAX_SOURCE_BYTES = 1024 * 1024


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


@dataclass(frozen=True)
class _RuleState:
    """적재된 룰셋의 스냅샷. 통째로 바꿔 끼워서 분석 중 교체가 일어나도 한 분석은 한 스냅샷만 본다."""

    rules: yara.Rules
    disabled: frozenset[str]
    version: int
    key: str


def _severity(meta: dict) -> str:
    return str(meta.get("severity", "MEDIUM")).upper()


class Analyzer:
    """YARA 매칭만 수행한다. 매직바이트·PE·엔트로피와 워커 프로세스 격리는 이후 단계에서 붙는다."""

    def __init__(self, rules_dir: Path):
        rule_files = {path.stem: str(path) for path in sorted(rules_dir.glob("*.yar"))}
        if not rule_files:
            raise ValueError(f"YARA 룰이 없습니다: {rules_dir}")
        self._state = _RuleState(yara.compile(filepaths=rule_files), frozenset(), 0, "")

    @property
    def version(self) -> int:
        return self._state.version

    @property
    def key(self) -> str:
        return self._state.key

    @staticmethod
    def _compile(source: str) -> yara.Rules:
        if len(source.encode()) > MAX_SOURCE_BYTES:
            raise ValueError(f"룰 원문이 {MAX_SOURCE_BYTES} 바이트를 넘습니다")
        # include 지시문으로 서버의 파일을 끌어오지 못하게 한다.
        return yara.compile(source=source, includes=False)

    @staticmethod
    def compile_source(source: str) -> list[tuple[str, str]]:
        """원문을 컴파일해 (룰 이름, 심각도) 목록을 돌려준다. 실패하면 yara.Error 계열(크기 초과는 ValueError)."""
        return [(rule.identifier, _severity(rule.meta)) for rule in Analyzer._compile(source)]

    def load(self, version: int, key: str, source: str, disabled: Iterable[str]) -> None:
        """컴파일에 성공해야만 교체한다. 실패하면 기존 룰셋이 그대로 남는다."""
        self._state = _RuleState(self._compile(source), frozenset(disabled), version, key)

    def analyze_file(self, path: Path) -> Analysis:
        """파일을 메모리에 올리지 않고 YARA가 경로로 직접 읽게 한다. 메모리 사용이 파일 크기와 무관하다."""
        started = time.monotonic()
        state = self._state
        hits = [
            RuleHit(
                rule_name=match.rule,
                severity=_severity(match.meta),
                matched_strings=[string.identifier for string in match.strings][:MAX_MATCHED_STRINGS],
            )
            for match in state.rules.match(filepath=str(path))
            if match.rule not in state.disabled
        ]
        duration_ms = int((time.monotonic() - started) * 1000)
        return Analysis(malicious=bool(hits), matches=hits, duration_ms=duration_ms)
