"""목데이터 생성 SQL을 api-server 마이그레이션(V1~)을 적용한 임시 Postgres에서 시험한다."""
import os
import subprocess
from pathlib import Path

import pytest
from testcontainers.postgres import PostgresContainer

SEED_SQL = Path(__file__).resolve().parents[1] / "seed.sql"
MIGRATIONS = Path("/migrations")
NOW = "2026-11-25T06:00:00Z"  # 수요일 15:00 KST
PCS, DAYS, PER_DAY = 20, 10, 20
ACTIVE_BLACKLIST = ["%064x" % (0xB1AC0000 + i) for i in range(60)]
INACTIVE_BLACKLIST = "d" * 64


def version(path):
    return int(path.name[1:].split("__")[0])


class Db:
    def __init__(self, pg):
        self.env = {**os.environ, "PGPASSWORD": "q"}
        self.base = ["psql", "-h", pg.get_container_host_ip(), "-p", str(pg.get_exposed_port(5432)), "-U", "q",
                     "-d", "platform", "-v", "ON_ERROR_STOP=1", "-At", "-q"]

    def run(self, sql=None, file=None, **variables):
        args = self.base + [f"-v{k}={v}" for k, v in variables.items()]
        args += ["-f", str(file)] if file else ["-c", sql]
        return subprocess.run(args, env=self.env, check=True, capture_output=True, text=True).stdout

    def seed(self, pcs=PCS, days=DAYS, per_day=PER_DAY, seed="0.42", now=NOW, **guard):
        guard = {"confirm_wipe": "yes", "expect_db": "platform", **guard}
        guard = {k: v for k, v in guard.items() if v is not None}
        return self.run(file=SEED_SQL, pcs=pcs, days=days, per_day=per_day, seed=seed, now=f"'{now}'", **guard)

    def one(self, sql):
        return self.run(sql).strip()

    def num(self, sql):
        return float(self.one(sql))


@pytest.fixture(scope="session")
def db():
    with PostgresContainer("postgres:17-alpine", username="q", password="q", dbname="platform") as pg:
        d = Db(pg)
        for migration in sorted(MIGRATIONS.glob("V*.sql"), key=version):
            d.run(file=migration)
        values = ", ".join(f"('SHA256', '{h}', 'MalwareBazaar: test')" for h in ACTIVE_BLACKLIST)
        d.run(f"INSERT INTO hash_blacklist(hash_type, hash_value, reason) VALUES {values}")
        d.run(f"INSERT INTO hash_blacklist(hash_type, hash_value, reason, is_active) "
              f"VALUES ('SHA256', '{INACTIVE_BLACKLIST}', 'old', false)")
        d.run(f"INSERT INTO hash_blacklist(hash_type, hash_value, reason) VALUES ('TLSH', 'T1{'C' * 70}', 'tlsh row')")
        d.run("INSERT INTO admin_users(admin_id, username, password_hash, role) "
              "VALUES (gen_random_uuid(), 'fixture-admin', 'x', 'ADMIN')")
        d.run("INSERT INTO hash_whitelist(hash_value, reason, added_by) "
              "SELECT '%s', 'keep', admin_id FROM admin_users WHERE username = 'fixture-admin'" % ("e" * 64))
        d.run("INSERT INTO file_fingerprints(sha256, tlsh, algorithm_version, provenance, calculation_status) "
              "VALUES ('%s', 'T1%s', 'tlsh-1', 'VERIFIED_UPLOAD', 'CALCULATED')" % (ACTIVE_BLACKLIST[0], "C" * 70))
        d.seed()
        yield d
