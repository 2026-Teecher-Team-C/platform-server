package com.teecherteamc.platform.verdict.application;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 요청 UUID는 인증된 agent와 원본 SHA에 한 번만 귀속된다. 평가가 없어도 소유권은 남긴다. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class EventDecisionEvidence {
    private final JdbcTemplate jdbc;

    public EventDecisionEvidence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void claim(UUID event, String sha, UUID agent) {
        if (event == null) return; // event가 없는 내부 호출에는 연결할 이벤트도 없다.
        if (agent == null) throw new SecurityException("authenticated agent required");
        String normalizedSha = sha == null || sha.isBlank() ? null : sha;
        // SubmitFile/ReportEvent 모두 event -> corpus -> file 순서다. 아직 없는 UUID도 직렬화한다.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(similarity_lock_key('event'), hashtext(?))", event.toString());
        jdbc.query("SELECT agent_id, sha256 FROM download_events WHERE event_id=?", rs -> {
            if (!agent.equals(rs.getObject("agent_id", UUID.class))
                    || (rs.getString("sha256") != null && !Objects.equals(normalizedSha, rs.getString("sha256")))) {
                throw new SecurityException("event owner or SHA conflict");
            }
        }, event);
        jdbc.update("""
                INSERT INTO event_decision_evidence(event_id, sha256, agent_id) VALUES (?, ?, ?)
                ON CONFLICT(event_id) DO NOTHING
                """, event, normalizedSha, agent);
        var row = jdbc.queryForMap("SELECT agent_id, sha256 FROM event_decision_evidence WHERE event_id=?", event);
        // 충돌을 무시한 성공으로 처리하지 않는다. 소유자와 SHA가 같을 때만 재시도를 허용한다.
        if (!agent.equals(row.get("agent_id")) || !Objects.equals(normalizedSha, row.get("sha256"))) {
            throw new SecurityException("event owner or SHA conflict");
        }
    }
}
