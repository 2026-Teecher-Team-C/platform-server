package com.teecherteamc.platform.event.infrastructure;

import com.teecherteamc.platform.event.domain.QuarantineRecorderPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class QuarantineRecorderPortImpl implements QuarantineRecorderPort {

    private final JdbcTemplate jdbcTemplate;

    QuarantineRecorderPortImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(
            UUID eventId,
            String sha256,
            UUID agentId,
            String originalFilename,
            long fileSize,
            String bucket,
            String key,
            Instant now) {
        // ON CONFLICT DO NOTHING: findAndDelete()가 행 잠금 없이 SELECT 후 DELETE라, 같은
        // sha256에 대해 동시에 들어온 두 ReportEvent 호출이 둘 다 같은 pending 행(같은
        // s3_bucket/s3_key)을 읽어버리면 uq_quarantine_files_s3 유니크 위반이 난다. Postgres는
        // 제약 위반이 나면 트랜잭션 전체를 중단시켜서(방금 flush한 download_events 저장까지
        // 같이 날아감) try/catch로 복구가 안 되므로, SQL 레벨에서 둘째 INSERT를 조용히 무시한다 —
        // 둘 다 같은 파일의 같은 격리 기록을 남기려던 것이라 결과적으로 안전하다.
        jdbcTemplate.update(
                """
                INSERT INTO quarantine_files
                    (quarantine_id, event_id, sha256, agent_id, original_filename, file_size,
                     s3_bucket, s3_key, quarantined_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (s3_bucket, s3_key) DO NOTHING
                """,
                UUID.randomUUID(), eventId, sha256, agentId, originalFilename, fileSize, bucket, key,
                Timestamp.from(now));
    }
}
