import subprocess

import pytest

from conftest import ACTIVE_BLACKLIST, DAYS, NOW, PCS, PER_DAY, SEED_SQL

SEED_SQL_TEXT = SEED_SQL.read_text()

KST = "AT TIME ZONE 'Asia/Seoul'"


def test_행_수는_pcs_days_per_day_근처(db):
    expected = PCS * DAYS * PER_DAY
    assert abs(db.num("SELECT count(*) FROM download_events") - expected) <= expected * 0.15
    assert db.num("SELECT count(*) FROM agents a JOIN devices d USING(device_id) WHERE d.hostname LIKE 'scale-%'") == PCS


def test_판정_비율(db):
    total = db.num("SELECT count(*) FROM download_events")
    blocked = db.num("SELECT count(*) FROM download_events WHERE decision = 'BLOCKED'")
    released = db.num("SELECT count(*) FROM download_events WHERE decision = 'RELEASED'")
    assert 0.005 <= blocked / total <= 0.02
    assert released / total > 0.95


def test_평일_업무시간에_몰린다(db):
    share = db.num(f"""SELECT avg((extract(isodow FROM created_at {KST}) <= 5
                                   AND extract(hour FROM created_at {KST}) BETWEEN 9 AND 17)::int)
                       FROM download_events""")
    assert share > 0.6


def _profile_cum(hours):
    """시간대 가중치(80%는 09~18시 균등, 20%는 하루 균등)의 하루 중 0~hours시까지 누적 비율."""
    return 0.8 * min(max((hours - 9) / 9, 0), 1) + 0.2 * hours / 24


def _expected_share(lo, hi):
    """seed.sql의 날짜(평일 1/주말 0.2, 기간 평균 1로 정규화) x 시간대 가중치로 [lo,hi)에 들어갈 PC당 하루치 배수."""
    from datetime import datetime, timedelta, timezone
    kst = timezone(timedelta(hours=9))
    now = datetime.fromisoformat(NOW.replace("Z", "+00:00")).astimezone(kst)
    today = now.replace(hour=0, minute=0, second=0, microsecond=0)
    base = {a: (1.0 if (today - timedelta(days=a)).isoweekday() <= 5 else 0.2) for a in range(DAYS + 1)}
    avg = sum(base.values()) / len(base)
    total = 0.0
    for a, w in base.items():
        start = today - timedelta(days=a)
        s, e = max(lo, start), min(hi, start + timedelta(days=1))
        if s < e:
            total += w / avg * (_profile_cum((e - start).total_seconds() / 3600) - _profile_cum((s - start).total_seconds() / 3600))
    return total


def _window_events(db, lo_sql, hi_sql):
    return db.num(f"SELECT count(*) FROM download_events WHERE created_at >= {lo_sql} AND created_at < {hi_sql}")


def test_최근_1일은_평일_하루치_근처이고_부풀지_않는다(db):
    from datetime import datetime, timedelta
    now = datetime.fromisoformat(NOW.replace("Z", "+00:00"))
    got = _window_events(db, f"'{NOW}'::timestamptz - interval '1 day'", f"'{NOW}'::timestamptz + interval '1 second'")
    expected = PCS * PER_DAY * _expected_share(now - timedelta(days=1), now)
    assert abs(got - expected) <= expected * 0.35
    # 하루치 수준이어야 한다: 가중치 정규화 후 평일 한 날(예: 화 24일 KST 전체)과 비슷하다(예전엔 +40~100% 부풀었다).
    tue = _window_events(db, "'2026-11-24 00:00+09'", "'2026-11-25 00:00+09'")
    assert tue * 0.7 <= got <= tue * 1.3
    assert db.num(f"SELECT count(*) FROM download_events WHERE created_at > '{NOW}'::timestamptz") == 0


def test_최근_7일은_기대_건수의_15퍼센트_안(db):
    from datetime import datetime, timedelta
    now = datetime.fromisoformat(NOW.replace("Z", "+00:00"))
    got = _window_events(db, f"'{NOW}'::timestamptz - interval '7 days'", f"'{NOW}'::timestamptz + interval '1 second'")
    expected = PCS * PER_DAY * _expected_share(now - timedelta(days=7), now)
    assert abs(got - expected) <= expected * 0.15


def test_이벤트는_날짜별로_한_문장씩_넣는다(db):
    # 물리 저장 순서(ctid)가 거의 KST 날짜 오름차순이면 날짜마다 따로 INSERT했다는 뜻이다(문장 하나면 날짜가 섞인다).
    unordered = db.num("""SELECT count(*) FROM (
        SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date d,
               lag((created_at AT TIME ZONE 'Asia/Seoul')::date) OVER (ORDER BY ctid) pd
        FROM download_events) s WHERE d < pd""")
    assert unordered <= DAYS  # 페이지 경계에서 한두 번 어긋날 수 있다. 문장 하나면 수천 건이 어긋난다
    assert db.num("SELECT count(DISTINCT (created_at AT TIME ZONE 'Asia/Seoul')::date) FROM download_events") >= DAYS
    assert "FOR d_ago IN" in SEED_SQL_TEXT


def test_일부_에이전트는_REVOKED(db):
    revoked = db.num("SELECT count(*) FROM agents a JOIN devices d USING(device_id) "
                     "WHERE d.hostname LIKE 'scale-%' AND a.status = 'REVOKED'")
    assert 1 <= revoked <= PCS * 0.1


def test_인기_파일은_여러_PC에서_반복(db):
    top_share = db.num("""WITH c AS (SELECT sha256, count(*) n, count(DISTINCT agent_id) pcs FROM download_events
                                      WHERE sha256 IS NOT NULL GROUP BY sha256),
                               r AS (SELECT n, row_number() OVER (ORDER BY n DESC) rn, count(*) OVER () total FROM c)
                          SELECT sum(n) / (SELECT sum(n) FROM c) FROM r WHERE rn <= ceil(total * 0.01)""")
    assert top_share > 0.2
    assert db.num("SELECT max(pcs) FROM (SELECT count(DISTINCT agent_id) pcs FROM download_events "
                  "WHERE sha256 IS NOT NULL GROUP BY sha256) s") >= 5


def test_차단_이벤트가_위협_뷰에_나온다(db):
    blocked = db.num("SELECT count(*) FROM download_events WHERE decision = 'BLOCKED'")
    in_view = db.num("""SELECT count(*) FROM download_events de JOIN download_event_threats t USING (event_id)
                        WHERE de.decision = 'BLOCKED' AND t.severity IS NOT NULL AND t.reason_type = de.decision_source""")
    assert blocked > 0 and in_view == blocked
    reasons = set(db.run("SELECT DISTINCT t.reason_type FROM download_event_threats t JOIN download_events de "
                         "USING (event_id) WHERE de.decision = 'BLOCKED'").split())
    assert reasons == {"BLACKLIST", "SIMILARITY", "ENGINE", "CACHE"}


def test_SIMILARITY_차단은_BLOCK_근거와_묶인다(db):
    n = db.num("SELECT count(*) FROM download_events WHERE decision = 'BLOCKED' AND decision_source = 'SIMILARITY'")
    bound = db.num("""SELECT count(*) FROM download_events de
                      JOIN event_decision_evidence e ON e.event_id = de.event_id AND e.agent_id = de.agent_id
                                                    AND e.sha256 = de.sha256 AND e.assessment_id = de.similarity_assessment_id
                      JOIN similarity_assessments a ON a.assessment_id = e.assessment_id AND a.classification = 'BLOCK'
                      WHERE de.decision = 'BLOCKED' AND de.decision_source = 'SIMILARITY'""")
    assert n > 0 and bound == n


def test_ENGINE_CACHE_차단은_매칭이_있다(db):
    shas = db.num("SELECT count(DISTINCT sha256) FROM download_events "
                  "WHERE decision = 'BLOCKED' AND decision_source IN ('ENGINE', 'CACHE')")
    bad = db.num("""SELECT count(*) FROM (
                        SELECT DISTINCT e.sha256 FROM download_events e
                        WHERE e.decision = 'BLOCKED' AND e.decision_source IN ('ENGINE', 'CACHE')) s
                    WHERE NOT EXISTS (
                        SELECT 1 FROM analyses a WHERE a.sha256 = s.sha256 AND a.status = 'SUCCESS'
                          AND (SELECT count(*) FROM analysis_matches m WHERE m.analysis_id = a.analysis_id) BETWEEN 1 AND 3)""")
    assert shas > 0 and bad == 0


def test_BLACKLIST_차단은_실제_블랙리스트_해시(db):
    used = set(db.run("SELECT DISTINCT sha256 FROM download_events "
                      "WHERE decision = 'BLOCKED' AND decision_source = 'BLACKLIST'").split())
    assert used and used <= set(ACTIVE_BLACKLIST)


def test_확인은_차단에만_달리고_오래된_차단이_더_많이_확인된다(db):
    assert db.num("""SELECT count(*) FROM download_event_acknowledgements a JOIN download_events e USING (event_id)
                     WHERE e.decision NOT IN ('BLOCKED', 'FAIL_CLOSE')""") == 0
    assert db.num("SELECT count(*) FROM download_event_acknowledgements") > 0
    old, recent = (db.num(f"""SELECT avg((a.event_id IS NOT NULL)::int) FROM download_events e
                              LEFT JOIN download_event_acknowledgements a USING (event_id)
                              WHERE e.decision IN ('BLOCKED', 'FAIL_CLOSE')
                                AND e.created_at {op} '{NOW}'::timestamptz - interval '7 days'""")
                   for op in ("<", ">="))
    assert old > recent


def test_두_번_돌려도_행_수_같다(db):
    tables = ["download_events", "analyses", "analysis_matches", "download_event_acknowledgements", "devices",
              "agents", "file_verdicts", "similarity_assessments", "event_decision_evidence"]
    count = lambda: [db.num(f"SELECT count(*) FROM {t}") for t in tables]  # noqa: E731
    before = count()
    db.seed()
    assert count() == before
    assert db.num("SELECT count(*) FROM hash_blacklist") == len(ACTIVE_BLACKLIST) + 2  # 블랙리스트는 건드리지 않는다
    assert db.num("SELECT count(*) FROM file_fingerprints") == 1  # 지문도 그대로
    assert db.num("SELECT count(*) FROM admin_users WHERE username = 'fixture-admin'") == 1


@pytest.mark.parametrize("guard", [
    {"confirm_wipe": None},                      # 확인 변수 없음
    {"confirm_wipe": "no"},                      # yes 가 아님
    {"expect_db": None},                         # DB 이름 없음
    {"expect_db": "production"},                 # DB 이름이 다름
])
def test_안전장치가_없거나_틀리면_아무것도_지우지_않고_실패한다(db, guard):
    db.run("INSERT INTO hash_whitelist(hash_value, reason, added_by) "
           "SELECT repeat('f', 64), 'keep2', admin_id FROM admin_users WHERE username = 'fixture-admin' "
           "ON CONFLICT DO NOTHING")
    tables = ["download_events", "analyses", "hash_whitelist", "agents", "file_verdicts", "hash_blacklist"]
    count = lambda: [db.num(f"SELECT count(*) FROM {t}") for t in tables]  # noqa: E731
    before = count()
    with pytest.raises(subprocess.CalledProcessError) as e:
        db.seed(**guard)
    assert "중단" in e.value.stderr or "psql" in e.value.stderr
    assert count() == before
    assert before[0] > 0 and before[2] >= 1
