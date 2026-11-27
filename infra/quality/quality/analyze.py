"""응답 + 서버 근거를 묶어 탐지율·오탐률 곡선을 그리고 임계값을 제안한다."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field

from quality.records import Evidence, Item, Response

CURRENT_BLOCK, CURRENT_WARN = 30, 70
BLOCK_MAX, WARN_MAX = 50, 70  # 서버 허용 범위(SimilarityThresholds)
BENIGN_WARN_LIMIT = 0.05
# 목록·판정 이력으로 결론 난 요청은 TLSH 단계까지 가지 않으므로 곡선에서 뺀다.
LISTED = {"DECISION_SOURCE_BLACKLIST", "DECISION_SOURCE_WHITELIST", "DECISION_SOURCE_CACHE"}
SIMILARITY = "DECISION_SOURCE_SIMILARITY"


@dataclass(frozen=True)
class Row:
    item_id: str
    set: str
    sha256: str
    family: str
    file_type: str
    variant: str
    decision: str
    source: str
    error: str
    distance: int | None
    classification: str
    matched_label: str
    has_evidence: bool = False


@dataclass
class Proposal:
    block: int
    warn: int
    block_clipped: bool
    notes: list[str] = field(default_factory=list)


def join(items: list[Item], responses: list[Response], evidence: list[Evidence]) -> list[Row]:
    by_item = {r.item_id: r for r in responses}
    by_event = {e.event_id: e for e in evidence}
    rows = []
    for item in items:
        resp = by_item.get(item.item_id)
        if resp is None:
            continue
        ev = by_event.get(resp.event_id)
        rows.append(Row(item.item_id, item.set, item.sha256, item.family, item.file_type, item.variant,
                        resp.decision, resp.source, resp.error, ev.distance if ev else None,
                        ev.classification if ev else "", ev.matched_label if ev else "", ev is not None))
    return rows


def missing_evidence(rows: list[Row]) -> int:
    """TLSH와 event_id를 보낸 요청은 서버가 근거 행을 반드시 만든다. 없으면 근거 파일이 다른 회차·에이전트 것이다."""
    return sum(1 for r in rows if not r.error and r.source not in LISTED and not r.has_evidence)


def same_family(family: str, matched_label: str) -> bool:
    """운영 블랙리스트 사유는 'MalwareBazaar: AgentTesla'처럼 출처가 앞에 붙는다."""
    label = matched_label.split(": ", 1)[-1].strip()
    return bool(family) and label == family


def eligible(rows: list[Row], set_name: str) -> list[Row]:
    return [r for r in rows if r.set == set_name and not r.error and r.source not in LISTED]


def _within(rows: list[Row], low: int, high: int) -> int:
    return sum(1 for r in rows if r.distance is not None and low < r.distance <= high)


def curve(rows: list[Row], set_name: str) -> list[tuple[int, float, int]]:
    pool = eligible(rows, set_name)
    total = len(pool) or 1
    return [(t, _within(pool, -1, t) / total, _within(pool, -1, t)) for t in range(WARN_MAX + 1)]


def propose(rows: list[Row]) -> Proposal:
    benign = eligible(rows, "B")
    notes = []
    distances = [r.distance for r in benign if r.distance is not None]
    ideal = (min(distances) - 1) if distances else BLOCK_MAX + 1
    block = max(0, min(ideal, BLOCK_MAX))
    clipped = ideal > BLOCK_MAX
    if clipped:
        notes.append(f"정상 파일 최소 거리로는 {ideal}까지 가능하지만 서버 상한 {BLOCK_MAX}로 잘랐다")
    total = len(benign) or 1
    warn = block + 1
    for w in range(block + 1, WARN_MAX + 1):
        if _within(benign, block, w) / total <= BENIGN_WARN_LIMIT:
            warn = w
    if _within(benign, block, warn) / total > BENIGN_WARN_LIMIT:
        notes.append(f"경고 구간을 최소({warn})로 잡아도 정상 파일 경고가 5%를 넘는다")
    return Proposal(block, warn, clipped, notes)


def _classify(distance: int | None, block: int, warn: int) -> str:
    if distance is None or distance > warn:
        return ""
    return "BLOCK" if distance <= block else "WARN"


def predict_mismatches(base_rows: list[Row], pass_rows: list[Row], block: int, warn: int) -> list[str]:
    """1회차(base)의 서버 거리로 (block, warn) 정책의 판정을 예측하고 2회차와 item_id로 맞춰 본다.

    차단은 응답(BLOCK/SIMILARITY)으로, 경고는 2회차 근거의 classification으로 본다. 2회차에 빠졌거나 오류가 난
    항목도 불일치로 센다(스펙: 모든 항목에서 같아야 한다). 2회차 요청은 event_id가 새로 생기므로 거리는 1회차에서 온다.
    """
    second = {r.item_id: r for r in pass_rows}
    out = []
    for r in eligible(base_rows, "M") + eligible(base_rows, "B"):
        got = second.get(r.item_id)
        if got is None or got.error:
            out.append(r.item_id)
            continue
        expected = _classify(r.distance, block, warn)
        blocked = got.decision == "DECISION_BLOCK" and got.source == SIMILARITY
        actual = "BLOCK" if blocked else ("WARN" if got.classification == "WARN" else "")
        if expected != actual:
            out.append(r.item_id)
    order = {r.item_id: n for n, r in enumerate(base_rows)}
    return sorted(out, key=order.__getitem__)


def _pct(n: int, total: int) -> str:
    return f"{(n / total * 100) if total else 0:.1f}%"


def report(rows: list[Row], proposal: Proposal, meta: dict) -> str:
    m, b = eligible(rows, "M"), eligible(rows, "B")
    lines = ["# 탐지 품질 측정 결과", ""]
    lines += [f"- {k}: {v}" for k, v in meta.items()]
    excluded = Counter((r.set, "오류 " + r.error if r.error else "목록 " + r.source) for r in rows
                       if r.error or r.source in LISTED)
    lines += ["", "## 데이터", "", "| 묶음 | 전체 | 곡선에 쓴 수 |", "|---|---|---|"]
    for name in ("M", "B", "V"):
        lines.append(f"| {name} | {sum(1 for r in rows if r.set == name)} | {len(eligible(rows, name))} |")
    if excluded:
        lines += ["", "| 묶음 | 뺀 이유 | 건수 |", "|---|---|---|"]
        lines += [f"| {s} | {why} | {n} |" for (s, why), n in sorted(excluded.items())]

    def rates(block: int, warn: int) -> tuple[int, int, int, int]:
        return _within(m, -1, block), _within(m, block, warn), _within(b, -1, block), _within(b, block, warn)

    now, new = rates(CURRENT_BLOCK, CURRENT_WARN), rates(proposal.block, proposal.warn)
    lines += ["", "## 결정", "",
              f"- 차단을 {CURRENT_BLOCK}에서 {proposal.block}로 바꾸면 "
              f"신종 악성 차단율 {_pct(now[0], len(m))} → {_pct(new[0], len(m))}, "
              f"정상 파일 차단 {now[2]}개 → {new[2]}개.",
              f"- 경고 상한을 {CURRENT_WARN}에서 {proposal.warn}로 바꾸면 "
              f"신종 악성 경고율 {_pct(now[1], len(m))} → {_pct(new[1], len(m))}, "
              f"정상 파일 경고 {now[3]}개 → {new[3]}개.", ""]
    lines += [f"- {note}" for note in proposal.notes]
    lines += ["", "## 곡선 (거리 ≤ t)", "", "| t | 신종 악성 | 정상 |", "|---|---|---|"]
    mc, bc = curve(rows, "M"), curve(rows, "B")
    for t in range(0, WARN_MAX + 1, 5):
        lines.append(f"| {t} | {mc[t][1] * 100:.1f}% ({mc[t][2]}) | {bc[t][1] * 100:.1f}% ({bc[t][2]}) |")

    lines += ["", f"## 패밀리별 (상위 10, 현재 차단 {CURRENT_BLOCK})", "",
              "| 패밀리 | 샘플 | 차단 | 경고 | 차단 중 최근접 라벨이 같은 패밀리 |", "|---|---|---|---|---|"]
    for family, count in Counter(r.family or "(없음)" for r in m).most_common(10):
        pool = [r for r in m if (r.family or "(없음)") == family]
        blocked = [r for r in pool if r.distance is not None and r.distance <= CURRENT_BLOCK]
        same = sum(1 for r in blocked if same_family(r.family, r.matched_label))
        lines.append(f"| {family} | {count} | {_pct(len(blocked), count)} | "
                     f"{_pct(_within(pool, CURRENT_BLOCK, CURRENT_WARN), count)} | {_pct(same, len(blocked))} |")

    lines += ["", "## 파일 형식별", "", "| 형식 | 샘플 | 차단 | 경고 |", "|---|---|---|---|"]
    for kind, count in Counter(r.file_type or "(없음)" for r in m).most_common(8):
        pool = [r for r in m if (r.file_type or "(없음)") == kind]
        lines.append(f"| {kind} | {count} | {_pct(_within(pool, -1, CURRENT_BLOCK), count)} | "
                     f"{_pct(_within(pool, CURRENT_BLOCK, CURRENT_WARN), count)} |")

    variants = [r for r in rows if r.set == "V" and r.variant != "origin" and not r.error]
    if variants:
        lines += ["", "## 변종", "", "| 변형 | 수 | SHA-256 차단 | TLSH 차단 | TLSH 경고 |", "|---|---|---|---|---|"]
        for name in sorted({r.variant for r in variants}):
            pool = [r for r in variants if r.variant == name]
            sha = sum(1 for r in pool if r.source == "DECISION_SOURCE_BLACKLIST")
            lines.append(f"| {name} | {len(pool)} | {_pct(sha, len(pool))} | "
                         f"{_pct(_within(pool, -1, CURRENT_BLOCK), len(pool))} | "
                         f"{_pct(_within(pool, CURRENT_BLOCK, CURRENT_WARN), len(pool))} |")
    errors = Counter(r.error for r in rows if r.error)
    if errors:
        lines += ["", "## 오류", ""] + [f"- {code}: {n}건" for code, n in errors.items()]
    return "\n".join(lines) + "\n"
