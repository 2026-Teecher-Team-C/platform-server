package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.PendingQuarantineUploadPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class PendingQuarantineUploadPortImpl implements PendingQuarantineUploadPort {

    private final JdbcTemplate jdbcTemplate;

    PendingQuarantineUploadPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(String sha256, long fileSize, String bucket, String key) {
        // ON CONFLICT DO NOTHING: 같은 sha256이 재분석(stale 캐시 재검사 등)되면서, ReportEvent가
        // 아직 소비 안 한 이전 pending 행이 남아있을 때 PK 충돌이 날 수 있다. Postgres는 제약 위반이
        // 나면 트랜잭션 전체를 중단 상태로 만들어서(뒤에 오는 saveAnalysis 등도 다 막힘) try/catch로
        // 복구할 수 없으므로, SQL 레벨에서 충돌을 애초에 예외로 만들지 않는다 — 기존 pending 행을
        // 그대로 둔다(이번 업로드가 먼저 ReportEvent에 소비될 뿐, 둘 다 같은 파일이라 안전하다).
        jdbcTemplate.update(
                """
                INSERT INTO pending_quarantine_uploads (sha256, file_size, s3_bucket, s3_key)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (sha256) DO NOTHING
                """,
                sha256, fileSize, bucket, key);
    }
}
