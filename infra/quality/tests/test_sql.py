"""운영 DB용 SQL을 api-server 마이그레이션(V1~)을 적용한 임시 Postgres에서 시험한다."""
import csv
import io
import os
import subprocess
import uuid
from pathlib import Path

import pytest
from testcontainers.postgres import PostgresContainer

SQL_DIR = Path(__file__).resolve().parents[1] / "sql"
MIGRATIONS = Path("/migrations")
TARGET = str(uuid.uuid4())
OTHER = str(uuid.uuid4())
TLSH = "T1" + "C" * 70


def version(path):
    return int(path.name[1:].split("__")[0])


@pytest.fixture(scope="module")
def psql():
    with PostgresContainer("postgres:17-alpine", username="q", password="q", dbname="platform") as pg:
        env = {**os.environ, "PGPASSWORD": "q"}
        base = ["psql", "-h", pg.get_container_host_ip(), "-p", str(pg.get_exposed_port(5432)), "-U", "q",
                "-d", "platform", "-v", "ON_ERROR_STOP=1", "-At", "-q"]

        def run(sql=None, file=None, **variables):
            args = base + [f"-v{k}={v}" for k, v in variables.items()]
            args += ["-f", str(file)] if file else ["-c", sql]
            return subprocess.run(args, env=env, check=True, capture_output=True, text=True).stdout

        for migration in sorted(MIGRATIONS.glob("V*.sql"), key=version):
            run(file=migration)
        seed(run)
        yield run


def seed(run):
    device = str(uuid.uuid4())
    run(f"INSERT INTO devices(device_id, hostname, os_platform) VALUES ('{device}', 'h', 'LINUX')")
    for agent, version in ((TARGET, "quality-harness"), (OTHER, "0.3.0")):
        run(f"INSERT INTO agents(agent_id, device_id, agent_version, enrollment_token_hash) "
            f"VALUES ('{agent}', '{device}', '{version}', 'x')")
    run(f"INSERT INTO hash_blacklist(hash_value, reason) VALUES ('{'a' * 64}', 'MalwareBazaar: Mirai')")
    run(f"INSERT INTO hash_blacklist(hash_value, reason, is_active) VALUES ('{'d' * 64}', 'old', false)")
    run(f"INSERT INTO hash_blacklist(hash_type, hash_value, reason) VALUES ('TLSH', '{TLSH}', 'tlsh row')")
    for agent, distance in ((TARGET, 12), (TARGET, None), (OTHER, 20)):
        event = str(uuid.uuid4())
        assessment = str(uuid.uuid4())
        matched = f"'{'a' * 64}', '{TLSH}', {distance}, 'BLOCK'" if distance is not None else "NULL, NULL, NULL, 'NONE'"
        run(f"INSERT INTO similarity_assessments(assessment_id, sha256, corpus_token, policy_version, "
            f"matched_sample_sha256, matched_sample_tlsh, distance, classification, input_source) "
            f"VALUES ('{assessment}', '{'b' * 64}', 't', 'tlsh-30-70-v1', {matched}, 'AGENT_CLAIMED')")
        run(f"INSERT INTO event_decision_evidence(event_id, agent_id, sha256, assessment_id) "
            f"VALUES ('{event}', '{agent}', '{'b' * 64}', '{assessment}')")


def rows(text):
    return list(csv.DictReader(io.StringIO(text)))


def test_근거_내보내기는_대상_에이전트만(psql):
    out = rows(psql(file=SQL_DIR / "export_evidence.sql", agent_id=TARGET))

    assert len(out) == 2
    assert set(out[0]) == {"event_id", "distance", "matched_sample_sha256", "classification", "policy_version",
                           "matched_label"}
    matched = next(r for r in out if r["distance"])
    assert (matched["distance"], matched["matched_label"], matched["classification"]) == ("12", "MalwareBazaar: Mirai", "BLOCK")
    assert next(r for r in out if not r["distance"])["matched_sample_sha256"] == ""


def test_블랙리스트_sha_내보내기는_해제된_것도_포함(psql):
    assert sorted(psql(file=SQL_DIR / "blacklist_shas.sql").split()) == ["a" * 64, "d" * 64]


def test_측정_에이전트를_막기만_하고_기록은_남긴다(psql):
    with pytest.raises(subprocess.CalledProcessError):
        psql(file=SQL_DIR / "revoke_agent.sql", agent_id=OTHER)
    assert psql(f"SELECT status FROM agents WHERE agent_id = '{OTHER}'").strip() == "ACTIVE"

    psql(file=SQL_DIR / "revoke_agent.sql", agent_id=TARGET)

    assert psql(f"SELECT status FROM agents WHERE agent_id = '{TARGET}'").strip() == "REVOKED"
    assert psql(f"SELECT count(*) FROM event_decision_evidence WHERE agent_id = '{TARGET}'").strip() == "2"
    psql(f"UPDATE agents SET status = 'ACTIVE' WHERE agent_id = '{TARGET}'")


def test_측정_에이전트가_아니면_정리를_거부하고_아무것도_지우지_않는다(psql):
    with pytest.raises(subprocess.CalledProcessError):
        psql(file=SQL_DIR / "cleanup.sql", agent_id=OTHER)

    assert psql(f"SELECT count(*) FROM event_decision_evidence WHERE agent_id = '{OTHER}'").strip() == "1"
    assert psql(f"SELECT status FROM agents WHERE agent_id = '{OTHER}'").strip() == "ACTIVE"


def test_정리는_대상_에이전트만_지운다(psql):
    preview = psql(file=SQL_DIR / "cleanup_preview.sql", agent_id=TARGET)
    assert "event_decision_evidence|2" in preview
    assert "similarity_assessments|2" in preview

    psql(file=SQL_DIR / "cleanup.sql", agent_id=TARGET)

    assert psql(f"SELECT count(*) FROM event_decision_evidence WHERE agent_id = '{TARGET}'").strip() == "0"
    assert psql(f"SELECT count(*) FROM event_decision_evidence WHERE agent_id = '{OTHER}'").strip() == "1"
    assert psql("SELECT count(*) FROM similarity_assessments").strip() == "1"
    assert psql(f"SELECT status FROM agents WHERE agent_id = '{TARGET}'").strip() == "REVOKED"
    assert psql(f"SELECT status FROM agents WHERE agent_id = '{OTHER}'").strip() == "ACTIVE"


def test_활성_룰셋_원문과_꺼진_룰_이름_내보내기(psql):
    source = 'rule on_a { condition: true }\nrule off_b { condition: false }\n'
    psql("INSERT INTO yara_rulesets(rulesets_version, rule_count, is_active, source) "
         "VALUES (90, 2, false, $q$other$q$)")
    psql("UPDATE yara_rulesets SET is_active = false")
    psql(f"INSERT INTO yara_rulesets(rulesets_version, rule_count, is_active, source) VALUES (91, 2, true, $q${source}$q$)")
    psql("INSERT INTO yara_rules(rule_id, rulesets_version, rule_name, severity, enabled) VALUES "
         "(gen_random_uuid(), 91, 'on_a', 'HIGH', true), (gen_random_uuid(), 91, 'off_b', 'HIGH', false), "
         "(gen_random_uuid(), 90, 'off_old', 'HIGH', false)")

    assert psql(file=SQL_DIR / "active_ruleset_source.sql") == source + "\n"  # psql이 줄바꿈을 하나 붙인다
    assert psql(file=SQL_DIR / "active_disabled_rules.sql") == "off_b\n"
