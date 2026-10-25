package com.teecherteamc.platform.policy.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.policy.application.ListBypassDomainsUseCase;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 바이패스 도메인 관리 REST 엔드포인트. 지금은 목록 조회만 있다.
 *
 * 로그인(access 토큰)이 필요하다. SecurityConfig가 막는다. (docs/auth-policy.md)
 */
@RestController
public class BypassDomainController {

    private static final Set<String> VALID_CATEGORIES = Set.of("PINNED", "SECURITY_UPDATE");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListBypassDomainsUseCase listBypassDomainsUseCase;

    public BypassDomainController(ListBypassDomainsUseCase listBypassDomainsUseCase) {
        this.listBypassDomainsUseCase = listBypassDomainsUseCase;
    }

    /**
     * 바이패스 도메인 목록. isActive를 생략하면 활성만 내려준다 — 에이전트 GetPolicy에 내려가는 목록과
     * 같은 집합이고, 콘솔 mock의 기본값과도 같다. 비활성까지 보려면 isActive=false를 명시한다.
     */
    @GetMapping("/api/admin/bypass-domains")
    public PageResponse<BypassDomainItem> list(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "true") boolean isActive,
            @RequestParam(required = false) String host,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        if (category != null && !VALID_CATEGORIES.contains(category)) {
            throw invalid("category must be one of " + VALID_CATEGORIES);
        }
        if (page < 0) {
            throw invalid("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }
        String hostKeyword = host == null || host.isBlank() ? null : host.strip();

        return PageResponse.from(
                listBypassDomainsUseCase.list(isActive, category, hostKeyword, page, size),
                BypassDomainItem::from);
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message);
    }
}
