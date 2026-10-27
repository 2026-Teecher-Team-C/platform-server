package com.teecherteamc.platform.policy.interfaces;

import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.policy.application.AddBypassDomainUseCase;
import com.teecherteamc.platform.policy.application.BypassDomainConflictException;
import com.teecherteamc.platform.policy.application.ListBypassDomainsUseCase;
import com.teecherteamc.platform.policy.domain.BypassDomain;
import com.teecherteamc.platform.policy.domain.InvalidBypassDomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 바이패스 도메인 관리 REST 엔드포인트. 목록 조회와 등록이 있다.
 *
 * 로그인(access 토큰)이 필요하다. SecurityConfig가 막는다. (docs/auth-policy.md)
 */
@RestController
public class BypassDomainController {

    private static final Set<String> VALID_CATEGORIES = Set.of("PINNED", "SECURITY_UPDATE");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListBypassDomainsUseCase listBypassDomainsUseCase;
    private final AddBypassDomainUseCase addBypassDomainUseCase;

    public BypassDomainController(ListBypassDomainsUseCase listBypassDomainsUseCase,
                                  AddBypassDomainUseCase addBypassDomainUseCase) {
        this.listBypassDomainsUseCase = listBypassDomainsUseCase;
        this.addBypassDomainUseCase = addBypassDomainUseCase;
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

    /**
     * 바이패스 도메인을 등록한다. 해제했던 host면 새로 만들지 않고 되살리며, 그 경우에도 201 + 본문으로 같게 응답한다.
     * 역할(ADMIN) 검사는 아직 하지 않는다. 로그인한 관리자면 누구나 등록할 수 있다.
     */
    @PostMapping("/api/admin/bypass-domains")
    @ResponseStatus(HttpStatus.CREATED)
    public BypassDomainItem create(@AuthenticationPrincipal AdminPrincipal principal,
                                   @Valid @RequestBody CreateBypassDomainRequest request,
                                   HttpServletRequest httpRequest) {
        try {
            BypassDomain domain = addBypassDomainUseCase.add(new AddBypassDomainUseCase.Command(
                    request.host(), request.category(), request.reason(), principal.adminId(),
                    ClientIp.resolve(httpRequest)));
            return BypassDomainItem.from(domain);
        } catch (InvalidBypassDomainException e) {
            // host 형식이 틀렸거나 모르는 category → 400. 메시지는 도메인이 쓴 문장을 그대로 쓴다.
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (BypassDomainConflictException e) {
            // 이미 활성으로 등록된 host → 409
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message);
    }
}
