package com.teecherteamc.platform.quarantine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 격리 파일 애그리거트 루트. 테이블: quarantine_files. package-info.java가 선언한 "QuarantinedFile"에
 * 해당한다(클래스명은 DB 테이블 이름에 맞춰 QuarantineFile로 둠).
 *
 * <p>목록/상세 조회는 이 애그리거트를 안 거치고 raw JdbcTemplate로 읽는다(읽기 전용이라 필요 없음) —
 * 이 애그리거트는 오탐 복원(쓰기)에서만 쓴다. 그래서 복원에 필요한 컬럼만 매핑한다
 * (original_filename/file_size/s3_bucket/s3_key/quarantined_at/expires_at은 복원 로직이 안 써서
 * 매핑 안 함 — ddl-auto: validate는 매핑 안 한 컬럼을 검증하지 않으므로 안전하다).
 *
 * <p>이 행은 event 컨텍스트가 격리 생성 시점에 raw JDBC로 만든다(QuarantineRecorderPort, 알려진
 * CLAUDE.md 위반 — 그건 별도 이슈). 이 애그리거트는 그 행을 나중에 복원할 때만 로드해서 쓴다.
 */
@Entity
@Table(name = "quarantine_files")
public class QuarantineFile {

    private static final String STATUS_QUARANTINED = "QUARANTINED";
    private static final String STATUS_RESTORED = "RESTORED";

    @Id
    @Column(name = "quarantine_id")
    private UUID quarantineId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "restored_at")
    private Instant restoredAt;

    @Column(name = "restored_by")
    private UUID restoredBy;

    @Column(name = "restore_reason", columnDefinition = "text")
    private String restoreReason;

    protected QuarantineFile() {
        // JPA 전용. 이 애그리거트는 항상 기존 행을 읽어서만 쓴다(새로 만들지 않음).
    }

    /**
     * 오탐 복원 처리한다. QUARANTINED 상태가 아니면(이미 복원됐거나 다른 상태) 거부한다 —
     * DB 체크 제약(ck_quarantine_files_restored)상 restored_at·restored_by는 항상 같이 채워야 한다.
     */
    public void restore(UUID adminId, String reason, Instant now) {
        if (!STATUS_QUARANTINED.equals(status)) {
            throw new QuarantineNotRestorableException(quarantineId, status);
        }
        this.status = STATUS_RESTORED;
        this.restoredAt = now;
        this.restoredBy = adminId;
        this.restoreReason = reason;
    }

    public UUID getQuarantineId() {
        return quarantineId;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStatus() {
        return status;
    }

    public Instant getRestoredAt() {
        return restoredAt;
    }

    public UUID getRestoredBy() {
        return restoredBy;
    }

    public String getRestoreReason() {
        return restoreReason;
    }
}
