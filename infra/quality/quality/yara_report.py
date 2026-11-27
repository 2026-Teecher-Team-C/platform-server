"""지난 주 TLSH 결과(responses/evidence)와 YARA 스캔 결과를 합쳐 마크다운 표 1~5를 만든다."""
from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass

from quality.analyze import Row, _pct, eligible
from quality.records import YaraResult

BLOCK = 30
WARN = 70
FAMILIES = ["Mirai", "", "EasyShell", "FunStealer", "RemcosRAT", "Prometei", "Snowlight", "WARDENStealer",
            "Formbook", "AgentTesla"]
_STATUS = {"timeout": "TIMEOUT", "oversize": "OVERSIZE", "error": "ERROR"}
_FAIL_CLOSE = ("TIMEOUT", "OVERSIZE", "ERROR")


@dataclass(frozen=True)
class Combined:
    item_id: str
    set: str
    family: str
    file_type: str
    tlsh_stage: str  # BLOCK | WARN | PASS
    yara: str  # HIT | MISS | TIMEOUT | OVERSIZE | ERROR | MISSING
    rules: str = ""
    scan_ms: int = 0


def _stage(distance: int | None) -> str:
    if distance is None or distance > WARN:
        return "PASS"
    return "BLOCK" if distance <= BLOCK else "WARN"


def _yara_state(y: YaraResult | None) -> str:
    if y is None or y.status == "missing":
        return "MISSING"
    if y.status == "ok":
        return "HIT" if y.rule_count > 0 else "MISS"
    return _STATUS.get(y.status, "ERROR")


def combine(rows: list[Row], yara: list[YaraResult]) -> list[Combined]:
    by_item = {y.item_id: y for y in yara}
    out = []
    for set_name in ("M", "B"):
        for r in eligible(rows, set_name):
            y = by_item.get(r.item_id)
            out.append(Combined(r.item_id, r.set, r.family, r.file_type, _stage(r.distance), _yara_state(y),
                                y.rules if y else "", y.scan_ms if y else 0))
    return out


def _received(rows: list[Combined], set_name: str) -> list[Combined]:
    return [c for c in rows if c.set == set_name and c.yara != "MISSING"]


def _caught(c: Combined) -> bool:
    return c.tlsh_stage == "BLOCK" or c.yara == "HIT"


def _line(label: str, n: int, total: int) -> str:
    return f"| {label} | {n} | {_pct(n, total)} |"


def _table1(all_m: list[Combined], m: list[Combined]) -> list[str]:
    n = len(m)
    tlsh = sum(c.tlsh_stage == "BLOCK" for c in m)
    hit = sum(c.yara == "HIT" for c in m)
    both = sum(_caught(c) for c in m)
    base_block = sum(c.tlsh_stage == "BLOCK" for c in all_m)
    out = ["## 1. 단계별 차단율 (M, YARA 결과를 받은 샘플 기준)", "",
           f"- 지난 발표 기준 n={len(all_m)}, TLSH 차단 {_pct(base_block, len(all_m))}"
           f" (YARA 결과를 받지 못한 {len(all_m) - n}개 포함)",
           f"- 이번 분모(받은 샘플) n={n}. TLSH와 YARA를 모두 이 집합에서 다시 계산했다", "",
           "| 구분 | 개수 | 비율 |", "|---|---|---|",
           _line("받은 샘플", n, n),
           _line("TLSH 차단", tlsh, n),
           _line("YARA 차단(룰 매칭)", hit, n),
           _line("TLSH 단독", sum(c.tlsh_stage == "BLOCK" and c.yara != "HIT" for c in m), n),
           _line("YARA 단독", sum(c.yara == "HIT" and c.tlsh_stage != "BLOCK" for c in m), n),
           _line("TLSH 또는 YARA", both, n),
           _line("둘 다 못 잡음", n - both, n),
           _line("YARA timeout", sum(c.yara == "TIMEOUT" for c in m), n),
           _line("YARA oversize", sum(c.yara == "OVERSIZE" for c in m), n),
           _line("YARA error", sum(c.yara == "ERROR" for c in m), n), "",
           "- YARA 차단은 룰 매칭만 센다. timeout·oversize·error는 별도 행이며 운영에서는 fail-close로 차단된다.", ""]
    return out


def _table2(m: list[Combined]) -> list[str]:
    rest = [c for c in m if c.tlsh_stage != "BLOCK"]
    out = ["## 2. TLSH 미차단 중 YARA 보완", "",
           "| 구분 | TLSH 미차단 | YARA가 잡음 | 비율 |", "|---|---|---|---|"]
    for label, pool in (("TLSH 미차단 전체", rest), ("경고", [c for c in rest if c.tlsh_stage == "WARN"]),
                        ("통과", [c for c in rest if c.tlsh_stage == "PASS"])):
        hit = sum(c.yara == "HIT" for c in pool)
        out.append(f"| {label} | {len(pool)} | {hit} | {_pct(hit, len(pool))} |")
    return out + [""]


def _group_rows(m: list[Combined], key: str, names: list[str]) -> list[str]:
    out = []
    for name in names:
        pool = [c for c in m if getattr(c, key) == name]
        n = len(pool)
        if not n:
            out.append(f"| {name or '(라벨 없음)'} | 0 | - | - | - |")
            continue
        both = sum(_caught(c) for c in pool)
        label = name or "(라벨 없음)"
        out.append(f"| {label} | {n} | {_pct(sum(c.tlsh_stage == 'BLOCK' for c in pool), n)} | "
                   f"{_pct(both, n)} | {_pct(n - both, n)} |")
    return out


def _table3(m: list[Combined]) -> list[str]:
    head = ["| {} | 샘플 | TLSH 차단 | TLSH 또는 YARA | 남은 빈틈 |", "|---|---|---|---|---|"]
    types = [t for t, _ in Counter(c.file_type for c in m).most_common(10)]
    return (["## 3. 패밀리·파일 형식별 합친 차단율", "", head[0].format("패밀리"), head[1]]
            + _group_rows(m, "family", FAMILIES) + ["", head[0].format("파일 형식"), head[1]]
            + _group_rows(m, "file_type", types) + [""])


def _table4(all_b: list[Combined], b: list[Combined]) -> list[str]:
    n = len(b)
    hit = [c for c in b if c.yara == "HIT"]
    rules = Counter(r for c in hit for r in c.rules.split(";") if r)
    out = ["## 4. 정상 파일(B) YARA 오탐", "",
           f"- B 대상 {len(all_b)}, 결과 없음 {len(all_b) - n} (분모에서 제외)", "", "| 구분 | 개수 | 비율 |", "|---|---|---|",
           _line("받은 샘플", n, n), _line("YARA 매칭(오탐)", len(hit), n),
           _line("YARA timeout", sum(c.yara == "TIMEOUT" for c in b), n),
           _line("YARA oversize", sum(c.yara == "OVERSIZE" for c in b), n),
           _line("YARA error", sum(c.yara == "ERROR" for c in b), n), "",
           "- timeout·oversize·error는 오탐에 세지 않는다. 운영에서는 fail-close로 차단된다.", "",
           "오탐을 많이 낸 룰 상위 10개", "", "| 룰 | 건수 |", "|---|---|"]
    out += [f"| {name} | {count} |" for name, count in sorted(rules.items(), key=lambda kv: (-kv[1], kv[0]))[:10]]
    return out + [""]


def _percentile(values: list[int], q: float) -> int:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(q * len(ordered)) - 1)]


def _table5(m: list[Combined], b: list[Combined]) -> list[str]:
    pool = m + b
    times = [c.scan_ms for c in pool if c.yara in ("HIT", "MISS")]
    out = ["## 5. 검사 시간", "", "운영 엔진 사양 근사치(1 CPU / 1GiB). M+B 합산, ok 항목만 센다(ms).", "",
           "| 구분 | 값 |", "|---|---|"]
    if times:
        out += [f"| p50 | {_percentile(times, 0.5)} |", f"| p95 | {_percentile(times, 0.95)} |",
                f"| 최대 | {max(times)} |"]
    else:
        out += ["| p50 | - |", "| p95 | - |", "| 최대 | - |"]
    out += [f"| timeout | {sum(c.yara == 'TIMEOUT' for c in pool)} |",
            f"| oversize | {sum(c.yara == 'OVERSIZE' for c in pool)} |", ""]
    return out


def render(combined: list[Combined], meta: dict) -> str:
    all_m = [c for c in combined if c.set == "M"]
    all_b = [c for c in combined if c.set == "B"]
    m, b = _received(combined, "M"), _received(combined, "B")
    lines = ["# TLSH + YARA 결합 측정 보고서", ""]
    lines += [f"- {k}: {v}" for k, v in meta.items()]
    if meta:
        lines.append("")
    lines += _table1(all_m, m) + _table2(m) + _table3(m) + _table4(all_b, b) + _table5(m, b)
    return "\n".join(lines)
