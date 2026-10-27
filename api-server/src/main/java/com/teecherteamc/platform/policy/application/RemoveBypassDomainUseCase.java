package com.teecherteamc.platform.policy.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.policy.domain.BypassDomain;
import com.teecherteamc.platform.policy.domain.BypassDomainRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 바이패스 도메인을 해제한다. (DELETE /api/admin/bypass-domains/{bypassId})
 * 행을 지우지 않고 비활성화해서 이력을 남긴다. 해제하면 그 host 는 다시 검사 대상이 된다.
 *
 * 없는 id 와 이미 해제된 id 는 같게 다룬다: 둘 다 BypassDomainNotFoundException. (화이트리스트 해제와 같은 규칙)
 */
@Service
public class RemoveBypassDomainUseCase {

    private final BypassDomainRepository bypassDomains;
    private final AuditLogRecorder auditLogRecorder;

    public RemoveBypassDomainUseCase(BypassDomainRepository bypassDomains, AuditLogRecorder auditLogRecorder) {
        this.bypassDomains = bypassDomains;
        this.auditLogRecorder = auditLogRecorder;
    }

    @Transactional   // 비활성화와 감사 로그가 한 트랜잭션이라 한쪽이 실패하면 둘 다 취소된다
    public void remove(UUID bypassId, UUID actorId, String ipAddress) {
        // 1. 활성인 행만 해제 대상이다. 없거나 이미 비활성이면 404 (컨트롤러가 바꾼다)
        BypassDomain domain = bypassDomains.findById(bypassId)
                .filter(BypassDomain::isActive)
                .orElseThrow(BypassDomainNotFoundException::new);

        // 2. 비활성화. category·reason·등록자는 그대로 남는다.
        domain.deactivate();
        bypassDomains.save(domain);

        // 3. 감사 로그. 해제한 host 의 값을 스냅샷으로 남긴다. (재등록하면 행의 category·reason 이 덮어써지기 때문)
        auditLogRecorder.record(actorId, AuditAction.REMOVE_BYPASS_DOMAIN, AuditTargetType.BYPASS_DOMAIN,
                domain.getId().toString(), detail(domain), ipAddress);
    }

    private static Map<String, Object> detail(BypassDomain domain) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("host", domain.getDomainPattern());
        detail.put("category", domain.getCategory().name());
        detail.put("reason", domain.getReason());
        return detail;
    }
}
