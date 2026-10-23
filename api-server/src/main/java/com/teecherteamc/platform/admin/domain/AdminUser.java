package com.teecherteamc.platform.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * 관리 콘솔 계정 애그리거트 루트. 테이블: admin_users.
 *
 * <p>비밀번호는 BCrypt 해시로만 들고 있다. 해시를 만들고 대조하는 일은 application 계층의
 * PasswordEncoder가 한다 — domain은 Spring Security에 의존하지 않는다.
 */
@Entity
@Table(name = "admin_users")
public class AdminUser implements Persistable<UUID> {

    @Id
    @Column(name = "admin_id")
    private UUID adminId;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private AdminRole role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected AdminUser() {
        // JPA
    }

    private AdminUser(UUID adminId, String username, String passwordHash, AdminRole role, Instant now) {
        this.adminId = adminId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
        this.isNew = true;
    }

    public static AdminUser create(String username, String passwordHash, AdminRole role, Instant now) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("계정명은 필수입니다.");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("비밀번호 해시는 필수입니다.");
        }
        return new AdminUser(UUID.randomUUID(), username, passwordHash, role, now);
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    public void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }

    @Override
    public UUID getId() {
        return adminId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getAdminId() {
        return adminId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AdminRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
