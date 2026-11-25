from quality.analyze import curve, join, predict_mismatches, propose, report
from quality.records import Evidence, Item, Response

TLSH = "T1" + "D" * 70
UNKNOWN = "DECISION_UNKNOWN"
BLOCK = "DECISION_BLOCK"
SIM = "DECISION_SOURCE_SIMILARITY"


def build(specs):
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


def test_예측_대조는_1회차_거리와_2회차_판정을_item_id로_맞춘다():
    base = build([spec("M", 20), spec("M", 20), spec("M", 28), spec("M")])
    second = [r.__class__(**{**r.__dict__, "distance": None, "classification": ""}) for r in base]
    second[0] = second[0].__class__(**{**second[0].__dict__, "decision": UNKNOWN,
                                      "source": "DECISION_SOURCE_UNSPECIFIED"})

    assert predict_mismatches(base, second, block=25) == [base[0].item_id, base[2].item_id]


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
