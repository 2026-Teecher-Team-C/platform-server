package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.verdict.domain.QuarantineUploadResult;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** S3 완료와 ReportEvent의 순서에 상관없이 같은 인증 이벤트에만 격리를 연결한다. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class EventQuarantineUploads {
    private final JdbcTemplate jdbc;
    private final EventDecisionEvidence evidence;

    public EventQuarantineUploads(JdbcTemplate jdbc, EventDecisionEvidence evidence) {
        this.jdbc = jdbc;
        this.evidence = evidence;
    }

    public void complete(UUID event, String sha, UUID agent, long size, QuarantineUploadResult upload) {
        evidence.claim(event, sha, agent);
        jdbc.update("""
                INSERT INTO pending_quarantine_uploads(event_id, sha256, file_size, s3_bucket, s3_key)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT(event_id) DO NOTHING
                """, event, sha, size, upload.bucket(), upload.key());
        link(event, sha, agent);
    }

    public boolean link(UUID event, String sha, UUID agent) {
        // 완료/보고 모두 같은 event advisory transaction lock을 사용한다. 단순한 존재 확인 뒤
        // INSERT가 아니므로, 보고 INSERT와 늦은 S3 완료 사이의 경계도 직렬화된다.
        evidence.claim(event, sha, agent);
        jdbc.update("""
                INSERT INTO quarantine_files
                    (quarantine_id, event_id, sha256, agent_id, original_filename, file_size,
                     s3_bucket, s3_key, quarantined_at)
                SELECT ?, e.event_id, e.sha256, e.agent_id, e.filename, p.file_size,
                       p.s3_bucket, p.s3_key, clock_timestamp()
                FROM pending_quarantine_uploads p
                JOIN download_events e ON e.event_id=p.event_id AND e.sha256=p.sha256
                JOIN event_decision_evidence d ON d.event_id=e.event_id AND d.sha256=e.sha256 AND d.agent_id=e.agent_id
                WHERE e.event_id=? AND e.agent_id=? AND e.sha256=? AND e.decision='BLOCKED'
                  AND NOT EXISTS (SELECT 1 FROM quarantine_files q WHERE q.event_id=e.event_id)
                ON CONFLICT(s3_bucket, s3_key) DO NOTHING
                """, UUID.randomUUID(), event, agent, sha);
        // 보고가 먼저라면 여기서 소비한다. RELEASED/FAIL_CLOSE도 남은 업로드를 다른 이벤트에
        // 넘기지 않는다. 아직 보고되지 않았을 때만 pending을 유지한다. 과거 판정/평가는 불변이다.
        jdbc.update("""
                DELETE FROM pending_quarantine_uploads p USING download_events e
                WHERE p.event_id=? AND e.event_id=p.event_id AND e.agent_id=? AND e.sha256=?
                """, event, agent, sha);
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM quarantine_files WHERE event_id=?)", Boolean.class, event));
    }
}
