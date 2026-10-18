package com.teecherteamc.platform.admin.application;

import com.teecherteamc.platform.admin.domain.AdminUser;
import com.teecherteamc.platform.admin.domain.AdminUserRepository;
import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아이디·비밀번호 확인. 세션을 만드는 일은 interfaces 계층이 한다.
 *
 * <p>실패해도 예외를 던지지 않고 빈 값을 돌려준다. 예외로 롤백하면 LOGIN_FAILED 감사 로그까지
 * 사라지기 때문이다. 실패 이유(없는 계정·틀린 비밀번호·비활성)는 감사 로그에만 남기고, 호출한 쪽에는
 * 구분 없이 실패만 알린다.
 */
@Service
public class AdminLoginUseCase {

    // BCrypt는 72바이트까지만 본다. 그보다 긴 비밀번호로는 계정을 만들 수 없으므로 대조할 필요도 없다.
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogRecorder auditLogRecorder;
    // 없는 계정에도 BCrypt 대조를 한 번 해서 응답 시간으로 계정 존재 여부가 드러나지 않게 한다.
    private final String dummyPasswordHash;

    public AdminLoginUseCase(AdminUserRepository adminUserRepository, PasswordEncoder passwordEncoder,
                             AuditLogRecorder auditLogRecorder) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogRecorder = auditLogRecorder;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Optional<AdminView> login(String username, String password, String ipAddress) {
        Optional<AdminUser> found = adminUserRepository.findByUsername(username);
        boolean passwordMatches = matches(password, found.map(AdminUser::getPasswordHash).orElse(dummyPasswordHash));

        if (found.isEmpty()) {
            return fail(username, "UNKNOWN_USER", ipAddress);
        }
        if (!passwordMatches) {
            return fail(username, "BAD_PASSWORD", ipAddress);
        }
        AdminUser adminUser = found.get();
        if (!adminUser.isActive()) {
            return fail(username, "INACTIVE", ipAddress);
        }

        adminUser.recordLogin(Instant.now());
        adminUserRepository.save(adminUser);
        auditLogRecorder.record(adminUser.getAdminId(), AuditAction.LOGIN, AuditTargetType.ADMIN_USER,
                adminUser.getAdminId().toString(), null, ipAddress);
        return Optional.of(AdminView.from(adminUser));
    }

    private boolean matches(String password, String passwordHash) {
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            return false;
        }
        return passwordEncoder.matches(password, passwordHash);
    }

    // 감사 로그 규격 3장: LOGIN_FAILED는 actor 없이 시도한 계정명을 target_id로 남긴다. 비밀번호는 남기지 않는다.
    private Optional<AdminView> fail(String username, String reason, String ipAddress) {
        auditLogRecorder.record(null, AuditAction.LOGIN_FAILED, AuditTargetType.ADMIN_USER, username,
                Map.of("attempted_username", username, "reason", reason), ipAddress);
        return Optional.empty();
    }
}
