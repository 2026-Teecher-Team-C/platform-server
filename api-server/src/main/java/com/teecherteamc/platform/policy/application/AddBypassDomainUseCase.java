package com.teecherteamc.platform.policy.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.policy.domain.BypassCategory;
import com.teecherteamc.platform.policy.domain.BypassDomain;
import com.teecherteamc.platform.policy.domain.BypassDomainRepository;
import com.teecherteamc.platform.policy.domain.BypassHost;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 바이패스 도메인을 등록한다. (POST /api/admin/bypass-domains)
 *
 * 세 갈래로 나뉜다.
 *  - 같은 host가 없다: 새 행을 만든다.
 *  - 해제된(is_active=false) 행이 있다: 새로 넣지 않고 되살린다. UNIQUE 제약이 is_active를 보지 않기 때문이다.
 *  - 활성 행이 있다: 409(BypassDomainConflictException).
 */
@Service
public class AddBypassDomainUseCase {

    /** domain_pattern UNIQUE 제약의 이름. 다른 제약 위반(예: created_by 외래키)을 409로 잘못 바꾸지 않기 위해 쓴다. */
    private static final String UNIQUE_CONSTRAINT = "uq_bypass_domains_pattern";

    private final BypassDomainRepository bypassDomains;
    private final AuditLogRecorder auditLogRecorder;
    private final Clock clock;   // 현재 시각을 밖에서 받아야 테스트에서 고정할 수 있다

    public AddBypassDomainUseCase(BypassDomainRepository bypassDomains, AuditLogRecorder auditLogRecorder,
                                  Clock clock) {
        this.bypassDomains = bypassDomains;
        this.auditLogRecorder = auditLogRecorder;
        this.clock = clock;
    }

    /** 요청 값을 한 덩어리로 묶는다. host·category는 아직 검증 전의 글자 그대로다. */
    public record Command(String host, String category, String reason, UUID actorId, String ipAddress) {
    }

    @Transactional   // 저장과 감사 로그가 한 트랜잭션이라 한쪽이 실패하면 둘 다 취소된다
    public BypassDomain add(Command command) {
        // 1. 형식 검증 + 정규화. 틀리면 InvalidBypassDomainException (컨트롤러가 400으로 바꾼다)
        BypassHost host = BypassHost.of(command.host());
        BypassCategory category = BypassCategory.from(command.category());

        Optional<BypassDomain> existing = bypassDomains.findByHost(host);

        BypassDomain domain;
        if (existing.isPresent()) {
            domain = existing.get();

            // 2. 이미 활성이면 거절
            if (domain.isActive()) {
                throw new BypassDomainConflictException("이미 바이패스로 등록된 host입니다.");
            }

            // 3. 해제했던 행이 있으면 새로 INSERT하지 않고 되살린다. (UNIQUE 충돌 방지)
            domain.reactivate(category, command.reason(), command.actorId());
            bypassDomains.save(domain);
        } else {
            // 4. 처음 등록
            domain = BypassDomain.register(host, category, command.reason(), command.actorId(), clock.instant());
            saveNew(domain);
        }

        // 5. 감사 로그. detail에는 등록한 내용만 담는다.
        auditLogRecorder.record(command.actorId(), AuditAction.ADD_BYPASS_DOMAIN, AuditTargetType.BYPASS_DOMAIN,
                domain.getId().toString(), detail(domain), command.ipAddress());
        return domain;
    }

    /**
     * 새 행을 저장한다. findByHost와 저장 사이에 다른 요청이 같은 host를 먼저 넣었다면 UNIQUE 위반이 난다.
     * 그 경우만 409로 바꾼다. (이 동시 등록은 잠금 없이 처리한다)
     */
    private void saveNew(BypassDomain domain) {
        try {
            bypassDomains.save(domain);
        } catch (DataIntegrityViolationException e) {
            if (mentionsUniqueConstraint(e)) {
                throw new BypassDomainConflictException("이미 바이패스로 등록된 host입니다.");
            }
            throw e;
        }
    }

    private static boolean mentionsUniqueConstraint(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(UNIQUE_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> detail(BypassDomain domain) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("host", domain.getDomainPattern());
        detail.put("category", domain.getCategory().name());
        detail.put("reason", domain.getReason());
        return detail;
    }
}
