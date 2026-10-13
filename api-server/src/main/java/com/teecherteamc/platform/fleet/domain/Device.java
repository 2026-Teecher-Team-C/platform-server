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
 * 물리 장비 애그리거트 루트. 테이블: devices.
 *
 * <p>hardware_uuid는 위조 가능한 에이전트 자기 보고값이라(proto 주석, V1 스키마 주석) 식별
 * 근거나 재사용 매칭 키로 쓰지 않는다 — RegisterAgent는 매칭 로직 없이 매번 새 Device를
 * 만든다. 같은 장비를 기존 device로 잇는 건 관리자가 콘솔에서 수동으로 하는 별도 작업이다.
 */
@Entity
@Table(name = "devices")
public class Device implements Persistable<UUID> {

    @Id
    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "hardware_uuid", length = 64)
    private String hardwareUuid;

    @Column(name = "hostname", nullable = false)
    private String hostname;

    @Column(name = "os_platform", nullable = false, length = 16)
    private String osPlatform;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected Device() {
        // JPA
    }

    private Device(UUID deviceId, String hardwareUuid, String hostname, String osPlatform, Instant now) {
        this.deviceId = deviceId;
        this.hardwareUuid = hardwareUuid;
        this.hostname = hostname;
        this.osPlatform = osPlatform;
        this.status = "ACTIVE";
        this.createdAt = now;
        this.updatedAt = now;
        this.isNew = true;
    }

    public static Device register(String hostname, String osPlatform, String hardwareUuidOrNull, Instant now) {
        return new Device(UUID.randomUUID(), hardwareUuidOrNull, hostname, osPlatform, now);
    }

    @Override
    public UUID getId() {
        return deviceId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getHardwareUuid() {
        return hardwareUuid;
    }

    public String getHostname() {
        return hostname;
    }

    public String getOsPlatform() {
        return osPlatform;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
