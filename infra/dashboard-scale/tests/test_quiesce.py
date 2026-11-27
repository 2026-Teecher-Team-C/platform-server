"""quiesce.sql: 측정 DB의 다른 세션을 끝내고 유휴를 확인한다. 안전장치(expect_db)가 틀리면 아무것도 끝내지 않는다."""
import subprocess
import time
from pathlib import Path

import pytest

QUIESCE_SQL = Path(__file__).resolve().parents[1] / "quiesce.sql"


def _hold_session(db, tag):
    """다른 세션 하나를 연다(오래 도는 쿼리). application_name으로 찾는다."""
    env = {**db.env, "PGAPPNAME": tag}
    proc = subprocess.Popen(db.base + ["-c", "SELECT pg_sleep(60)"], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    for _ in range(50):
        if db.num(f"SELECT count(*) FROM pg_stat_activity WHERE application_name = '{tag}' AND state = 'active'"):
            return proc
        time.sleep(0.1)
    proc.kill()
    raise AssertionError("세션이 열리지 않았다")


def test_다른_세션을_끝내고_유휴를_확인한다(db):
    proc = _hold_session(db, "quiesce-victim")
    try:
        out = subprocess.run(db.base + ["-v", "expect_db=platform", "-f", str(QUIESCE_SQL)],
                             env=db.env, check=True, capture_output=True, text=True)
        assert "유휴 확인" in out.stderr
        assert proc.wait(timeout=10) != 0  # 끊긴 쪽 psql은 오류로 끝난다
        assert db.num("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'quiesce-victim'") == 0
    finally:
        proc.kill()


@pytest.mark.parametrize("expect", [None, "production"])
def test_DB_이름이_없거나_다르면_아무것도_끝내지_않는다(db, expect):
    tag = f"quiesce-survivor-{expect}"
    proc = _hold_session(db, tag)
    try:
        args = db.base + (["-v", f"expect_db={expect}"] if expect else []) + ["-f", str(QUIESCE_SQL)]
        with pytest.raises(subprocess.CalledProcessError):
            subprocess.run(args, env=db.env, check=True, capture_output=True, text=True)
        assert db.num(f"SELECT count(*) FROM pg_stat_activity WHERE application_name = '{tag}'") == 1
    finally:
        db.run(f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = '{tag}'")
        proc.kill()
        proc.wait()
