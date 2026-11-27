from quality.analyze import curve, join, missing_evidence, predict_mismatches, propose, report, same_family
from quality.records import Evidence, Item, Response

TLSH = "T1" + "D" * 70
UNKNOWN = "DECISION_UNKNOWN"
BLOCK = "DECISION_BLOCK"
SIM = "DECISION_SOURCE_SIMILARITY"


def build(specs, with_empty_evidence=False):
    """specs: (set, distance|None, family, source, error, variant)"""
    items, responses, evidence = [], [], []
    for n, (set_name, distance, family, source, error, variant) in enumerate(specs):
        item_id = f"{set_name}-{n}"
        items.append(Item(item_id, set_name, format(n, "064x"), TLSH, 100, family, "exe", variant, ""))
        decision = BLOCK if source in (SIM, "DECISION_SOURCE_BLACKLIST") else UNKNOWN
        responses.append(Response(item_id, f"e{n}", "" if error else decision, "" if error else source, "", error,
                                  "p1"))
        if distance is not None:
            cls = "BLOCK" if distance <= 30 else "WARN"
            evidence.append(Evidence(f"e{n}", distance, "f" * 64, cls, "tlsh-30-70-v1", family))
        elif with_empty_evidence:
            evidence.append(Evidence(f"e{n}", None, "", "", "", ""))
    return join(items, responses, evidence)


def spec(set_name, distance=None, family="", source="DECISION_SOURCE_UNSPECIFIED", error="", variant=""):
    if distance is not None and distance <= 30 and source == "DECISION_SOURCE_UNSPECIFIED":
        source = SIM
    return (set_name, distance, family, source, error, variant)


def test_분모에서_오류와_목록_판정을_뺀다():
    rows = build([spec("M", 10), spec("M"), spec("M", error="UNAVAILABLE"),
                  spec("M", source="DECISION_SOURCE_BLACKLIST")])

    t30 = dict((t, (rate, n)) for t, rate, n in curve(rows, "M"))[30]

    assert t30 == (0.5, 1)


def test_매칭_없는_행은_70에서도_미탐지():
    rows = build([spec("M", 70), spec("M")])

    points = {t: rate for t, rate, _ in curve(rows, "M")}

    assert points[69] == 0.0
    assert points[70] == 0.5
    assert len(points) == 71


def test_제안_규칙():
    rows = build([spec("B", d) for d in (12, 40, 41, 66)] + [spec("B")] * 46 + [spec("M", 5)] * 10)

    proposal = propose(rows)

    assert proposal.block == 11
    assert proposal.warn == 40
    assert not proposal.block_clipped


def test_정상_파일이_멀면_차단은_상한_50():
    rows = build([spec("B", 60)] + [spec("B")] * 40)

    proposal = propose(rows)

    assert proposal.block == 50
    assert proposal.block_clipped
    assert proposal.warn > proposal.block


def _second(base, **changes_by_index):
    rows = []
    for n, r in enumerate(base):
        d = r.distance
        cls = "BLOCK" if d is not None and d <= 25 else ("WARN" if d is not None and d <= 60 else "")
        decision, source = (BLOCK, SIM) if cls == "BLOCK" else (UNKNOWN, "DECISION_SOURCE_UNSPECIFIED")
        row = r.__class__(**{**r.__dict__, "decision": decision, "source": source, "classification": cls})
        rows.append(row.__class__(**{**row.__dict__, **changes_by_index.get(f"i{n}", {})}))
    return rows


def test_예측_대조는_1회차_거리와_2회차_판정을_item_id로_맞춘다():
    base = build([spec("M", 20), spec("M", 20), spec("M", 28), spec("M")])
    second = _second(base, i0={"decision": UNKNOWN, "source": "DECISION_SOURCE_UNSPECIFIED"},
                     i2={"decision": BLOCK, "source": SIM, "classification": "BLOCK"})

    assert predict_mismatches(base, second, block=25, warn=60) == [base[0].item_id, base[2].item_id]


def test_예측_대조는_경고도_본다():
    base = build([spec("M", 40), spec("M", 65)])
    second = _second(base, i1={"classification": "WARN"})

    assert predict_mismatches(base, second, block=25, warn=60) == [base[1].item_id]


def test_예측_대조는_2회차에_빠지거나_오류난_항목도_불일치로_센다():
    base = build([spec("M", 10), spec("M", 10)])
    second = _second(base, i0={"error": "UNAVAILABLE"})[:1]

    assert predict_mismatches(base, second, block=25, warn=60) == [base[0].item_id, base[1].item_id]


def test_근거_행이_없는_요청을_센다():
    rows_ok = build([spec("M", 10), spec("M")], with_empty_evidence=True)
    rows_bad = build([spec("M", 10), spec("M")])

    assert missing_evidence(rows_ok) == 0
    assert missing_evidence(rows_bad) == 1


def test_같은_패밀리_비교는_서버_사유_접두를_뗀다():
    assert same_family("AgentTesla", "MalwareBazaar: AgentTesla")
    assert same_family("Mirai", "Mirai")
    assert not same_family("Mirai", "MalwareBazaar: Gafgyt")
    assert not same_family("", "MalwareBazaar: ")


def test_결정_문장과_표():
    rows = build([spec("M", 10, "Mirai"), spec("M", 25, "Mirai"), spec("M", 45, "Lumma"), spec("M", None, "Lumma"),
                  spec("B", 33), spec("B")] + [spec("B")] * 18
                 + [spec("V", 3, variant="append-1k"), spec("V", 80 if False else None, variant="patch-5%")])

    text = report(rows, propose(rows), {"측정일": "2026-11-26"})

    assert "차단을 30에서 32로 바꾸면" in text
    assert "신종 악성 차단율 50.0% → 50.0%" in text
    assert "| Mirai |" in text and "| Lumma |" in text
    assert "| append-1k |" in text and "| patch-5% |" in text
    assert "측정일" in text
