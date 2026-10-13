package com.teecherteamc.platform.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * 관리자가 미리 발급하는 1회용 등록 토큰 애그리거트 루트. 테이블: agent_enrollment_tokens.
 *
 * <p>agents와 분리된 테이블인 이유: 발급 시점엔 아직 device/agent 행이 없다. redeem()이
 * 성공해야 RegisterAgentUseCase가 그제서야 Device/Agent를 만든다.
 *
 * <p>{@code @Version}이 필요한 이유: redeem()은 메모리에서 읽기→수정→쓰기라, 같은 토큰으로
 * 동시에 두 RegisterAgent 요청이 들어오면 둘 다 "아직 안 쓰임"을 보고 통과해버린다. 낙관적 락이
 * 없으면 DB에 토큰 UNIQUE 제약이 있어도 "1회용"이 실제로 지켜지지 않는다 — 나중 커밋이 버전
 * 불일치로 실패해야 비로소 막힌다.
 */
@Entity
@Table(name = "agent_enrollment_tokens")
public class EnrollmentToken implements Persistable<UUID> {

    @Id
    @Column(name = "enrollment_token_id")
    private UUID enrollmentTokenId;

    @Column(name = "enrollment_token_hash", nullable = false, length = 255)
    private String enrollmentTokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "issued_by")
    private UUID issuedBy;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "redeemed_by_agent_id")
    private UUID redeemedByAgentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean isNew;

    protected EnrollmentToken() {
        // JPA
    }

    private EnrollmentToken(UUID enrollmentTokenId, String enrollmentTokenHash, Instant expiresAt, UUID issuedBy,
            Instant now) {
        this.enrollmentTokenId = enrollmentTokenId;
        this.enrollmentTokenHash = enrollmentTokenHash;
        this.expiresAt = expiresAt;
        this.issuedBy = issuedBy;
        this.redeemedAt = null;
        this.redeemedByAgentId = null;
        this.createdAt = now;
        this.isNew = true;
    }

    public static EnrollmentToken issue(String enrollmentTokenHash, Instant expiresAt, UUID issuedByOrNull, Instant now) {
        return new EnrollmentToken(UUID.randomUUID(), enrollmentTokenHash, expiresAt, issuedByOrNull, now);
    }

    /** 제출된 토큰이 이 행과 일치한다고 확인된 뒤 호출한다 — 만료/재사용 여부를 여기서 최종 확정한다. */
    public void redeem(UUID agentId, Instant now) {
        if (redeemedAt != null) {
            throw new EnrollmentTokenAlreadyRedeemedException();
        }
        if (now.isAfter(expiresAt)) {
            throw new EnrollmentTokenExpiredException();
        }
        this.redeemedAt = now;
        this.redeemedByAgentId = agentId;
    }

    @Override
    public UUID getId() {
        return enrollmentTokenId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getEnrollmentTokenHash() {
        return enrollmentTokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getIssuedBy() {
        return issuedBy;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }

    public UUID getRedeemedByAgentId() {
        return redeemedByAgentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
