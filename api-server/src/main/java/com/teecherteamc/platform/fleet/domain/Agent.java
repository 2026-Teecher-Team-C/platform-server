package com.teecherteamc.platform.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * 에이전트 설치 인스턴스 애그리거트 루트. 테이블: agents.
 *
 * <p>deviceId는 Device 애그리거트를 @ManyToOne으로 참조하지 않고 순수 UUID 컬럼으로 둔다 —
 * event 컨텍스트의 DownloadEvent가 agentId/sha256을 스칼라로 두는 것과 같은 이유로, fleet
 * 내부에서도 두 애그리거트(Device/Agent)를 참조로 엮지 않는다.
 */
@Entity
@Table(name = "agents")
public class Agent implements Persistable<UUID> {

    @Id
    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "agent_version", nullable = false, length = 32)
    private String agentVersion;

    @Column(name = "enrollment_token_hash", nullable = false, length = 255)
    private String enrollmentTokenHash;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "agent_token_hash", length = 255)
    private String agentTokenHash;

    @Column(name = "agent_token_expires_at")
    private Instant agentTokenExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected Agent() {
        // JPA
    }

    private Agent(
            UUID agentId,
            UUID deviceId,
            String agentVersion,
            String enrollmentTokenHash,
            String agentTokenHash,
            Instant agentTokenExpiresAt,
            Instant now) {
        this.agentId = agentId;
        this.deviceId = deviceId;
        this.agentVersion = agentVersion;
        this.enrollmentTokenHash = enrollmentTokenHash;
        this.status = "ACTIVE";
        this.lastHeartbeatAt = null;
        this.agentTokenHash = agentTokenHash;
        this.agentTokenExpiresAt = agentTokenExpiresAt;
        this.createdAt = now;
        this.updatedAt = now;
        this.isNew = true;
    }

    public static Agent register(
            UUID agentId,
            UUID deviceId,
            String agentVersion,
            String enrollmentTokenHash,
            String agentTokenHash,
            Instant agentTokenExpiresAt,
            Instant now) {
        return new Agent(agentId, deviceId, agentVersion, enrollmentTokenHash, agentTokenHash, agentTokenExpiresAt, now);
    }

    @Override
    public UUID getId() {
        return agentId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public String getEnrollmentTokenHash() {
        return enrollmentTokenHash;
    }

    public String getStatus() {
        return status;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public String getAgentTokenHash() {
        return agentTokenHash;
    }

    public Instant getAgentTokenExpiresAt() {
        return agentTokenExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
