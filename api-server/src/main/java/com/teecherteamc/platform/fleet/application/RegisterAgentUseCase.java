package com.teecherteamc.platform.fleet.application;

import com.teecherteamc.platform.fleet.domain.Agent;
import com.teecherteamc.platform.fleet.domain.AgentRepository;
import com.teecherteamc.platform.fleet.domain.Device;
import com.teecherteamc.platform.fleet.domain.DeviceRepository;
import com.teecherteamc.platform.fleet.domain.EnrollmentToken;
import com.teecherteamc.platform.fleet.domain.EnrollmentTokenRepository;
import com.teecherteamc.platform.fleet.domain.EnrollmentTokenAlreadyRedeemedException;
import com.teecherteamc.platform.fleet.domain.InvalidEnrollmentTokenException;
import com.teecherteamc.platform.fleet.domain.TokenHash;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록 토큰을 소비해 새 Device+Agent를 만들고 운영용 agent_token을 발급한다. hardware_uuid로
 * 기존 Device를 찾아 재사용하는 로직은 의도적으로 없다 — Device.register()가 매번 새로 만든다
 * (재사용 매칭은 관리자가 콘솔에서 수동으로 하는 별도 범위).
 */
@Service
public class RegisterAgentUseCase {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository deviceRepository;
    private final AgentRepository agentRepository;
    private final EnrollmentTokenRepository enrollmentTokenRepository;
    private final Duration agentTokenTtl;

    public RegisterAgentUseCase(
            DeviceRepository deviceRepository,
            AgentRepository agentRepository,
            EnrollmentTokenRepository enrollmentTokenRepository,
            @Value("${fleet.agent-token.ttl:P90D}") Duration agentTokenTtl) {
        this.deviceRepository = deviceRepository;
        this.agentRepository = agentRepository;
        this.enrollmentTokenRepository = enrollmentTokenRepository;
        this.agentTokenTtl = agentTokenTtl;
    }

    @Transactional
    public RegisterAgentResult register(RegisterAgentCommand command) {
        String enrollmentTokenHash = TokenHash.sha256Hex(command.enrollmentToken());
        EnrollmentToken enrollmentToken = enrollmentTokenRepository
                .findByEnrollmentTokenHash(enrollmentTokenHash)
                .filter(t -> TokenHash.matches(t.getEnrollmentTokenHash(), enrollmentTokenHash))
                .orElseThrow(InvalidEnrollmentTokenException::new);

        UUID agentId = UUID.randomUUID();
        Instant now = Instant.now();
        // redeem()이 만료/재사용 여부를 최종 확정한다 — 여기서 먼저 던지면 Device/Agent를 만들기 전에 끝난다.
        enrollmentToken.redeem(agentId, now);

        Device device = deviceRepository.save(
                Device.register(command.hostname(), command.osPlatform(), command.hardwareUuidOrNull(), now));

        String agentToken = generateAgentToken();
        String agentTokenHash = TokenHash.sha256Hex(agentToken);
        Instant tokenExpiresAt = now.plus(agentTokenTtl);

        Agent agent = agentRepository.save(Agent.register(
                agentId, device.getDeviceId(), command.agentVersion(), enrollmentTokenHash, agentTokenHash,
                tokenExpiresAt, now));

        try {
            // version이 메모리에서 읽은 시점과 다르면(동시에 다른 요청이 먼저 redeem해서 커밋했다는 뜻)
            // 여기서 실패한다 — 트랜잭션 전체가 롤백돼 위에서 만든 Device/Agent도 함께 사라진다.
            enrollmentTokenRepository.save(enrollmentToken);
        } catch (OptimisticLockingFailureException e) {
            throw new EnrollmentTokenAlreadyRedeemedException();
        }

        return new RegisterAgentResult(agent.getAgentId(), agentToken, tokenExpiresAt);
    }

    private static String generateAgentToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
