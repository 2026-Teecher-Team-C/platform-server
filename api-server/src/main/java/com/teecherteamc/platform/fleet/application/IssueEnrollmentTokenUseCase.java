package com.teecherteamc.platform.fleet.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.fleet.domain.EnrollmentToken;
import com.teecherteamc.platform.fleet.domain.EnrollmentTokenRepository;
import com.teecherteamc.platform.fleet.domain.TokenHash;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 PC에 에이전트를 설치할 때 쓰는 1회용 등록 토큰을 발급한다.
 *
 * <p>평문 토큰은 DB에 저장하지 않는다. TokenHash.sha256Hex로 해시한 값만 agent_enrollment_tokens에
 * 넣는다. RegisterAgentUseCase가 같은 TokenHash로 해시해서 찾기 때문에, 해시 방식을 여기서
 * 바꾸면 발급한 토큰으로 등록이 안 된다.
 *
 * <p>평문은 결과(Result)로 한 번 돌려주고 끝이다. 다시 조회하는 방법은 없다.
 */
@Service
public class IssueEnrollmentTokenUseCase {

    /** 평문 토큰 앞에 붙이는 접두어. 로그나 화면에서 등록 토큰인지 구분하기 쉽게 한다. */
    private static final String TOKEN_PREFIX = "enr_";

    // SecureRandom은 만들 때 비용이 있고 스레드 안전해서 하나를 공유한다 (RegisterAgentUseCase와 같은 방식)
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EnrollmentTokenRepository enrollmentTokenRepository;
    private final Clock clock;
    private final AuditLogRecorder auditLogRecorder;

    public IssueEnrollmentTokenUseCase(
            EnrollmentTokenRepository enrollmentTokenRepository, Clock clock, AuditLogRecorder auditLogRecorder) {
        this.enrollmentTokenRepository = enrollmentTokenRepository;
        this.clock = clock;
        this.auditLogRecorder = auditLogRecorder;
    }

    /**
     * @param expiresInMinutes 컨트롤러에서 범위 검증을 끝낸 값 (기본값 적용도 컨트롤러에서 한다)
     * @param actorId          발급한 관리자 id. issued_by와 감사 로그 actor에 같이 쓴다
     * @param ip               감사 로그에 남길 요청 IP
     */
    public record Command(long expiresInMinutes, UUID actorId, String ip) {}

    /** token은 평문이다. 응답에 한 번 실리고 어디에도 저장되지 않는다. */
    public record Result(String token, Instant expiresAt) {}

    @Transactional
    public Result issue(Command command) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofMinutes(command.expiresInMinutes()));

        String plainToken = generatePlainToken();
        EnrollmentToken saved = enrollmentTokenRepository.save(
                EnrollmentToken.issue(TokenHash.sha256Hex(plainToken), expiresAt, command.actorId(), now));

        // 감사 로그에는 토큰 id와 만료 시각만 남긴다. 평문과 해시는 넣지 않는다.
        // record()가 MANDATORY라서 이 트랜잭션 안에서 불러야 하고, 로그가 실패하면 발급도 같이 롤백된다.
        auditLogRecorder.record(
                command.actorId(),
                AuditAction.ISSUE_ENROLLMENT_TOKEN,
                AuditTargetType.ENROLLMENT_TOKEN,
                saved.getId().toString(),
                Map.of("expiresAt", expiresAt.toString()),
                command.ip());

        return new Result(plainToken, expiresAt);
    }

    /** 32바이트 난수를 Base64 URL 문자열로 바꾸고 접두어를 붙인다 (RegisterAgentUseCase.generateAgentToken과 같은 방식). */
    private static String generatePlainToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}