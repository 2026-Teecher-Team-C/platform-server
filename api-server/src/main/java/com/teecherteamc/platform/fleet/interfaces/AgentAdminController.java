package com.teecherteamc.platform.fleet.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.fleet.application.ListAgentsUseCase;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트(등록된 PC) 관리 REST 엔드포인트.
 *
 * 인증은 SecurityConfig가 걸고, 여기서는 쿼리 파라미터 검증과 응답 변환만 한다.
 * 목록 응답은 API 컨벤션의 PageResponse(page는 0부터)로 내린다.
 */
@RestController
public class AgentAdminController {

    private static final List<String> VALID_STATUSES = List.of("ACTIVE", "INACTIVE", "REVOKED");
    private static final List<String> VALID_OS_PLATFORMS = List.of("WINDOWS", "MACOS", "LINUX");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListAgentsUseCase listAgentsUseCase;

    public AgentAdminController(ListAgentsUseCase listAgentsUseCase) {
        this.listAgentsUseCase = listAgentsUseCase;
    }

    /** 에이전트 목록. 생략한 필터는 걸지 않는다. 최근 하트비트가 위에 온다. */
    @GetMapping("/api/admin/agents")
    public PageResponse<AgentListItem> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String hostname,
            @RequestParam(required = false) String osPlatform,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        // 빈 문자열(?status=)은 필터를 안 건 것으로 본다.
        String statusFilter = blankToNull(status);
        if (statusFilter != null && !VALID_STATUSES.contains(statusFilter)) {
            throw invalid("status must be one of " + VALID_STATUSES);
        }
        String osFilter = blankToNull(osPlatform);
        if (osFilter != null && !VALID_OS_PLATFORMS.contains(osFilter)) {
            throw invalid("osPlatform must be one of " + VALID_OS_PLATFORMS);
        }
        String hostnameFilter = blankToNull(hostname);   // 부분 일치라서 형식 제한은 두지 않는다
        if (page < 0) {
            throw invalid("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }

        return PageResponse.from(
                listAgentsUseCase.list(statusFilter, hostnameFilter, osFilter, page, size),
                AgentListItem::from);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message);
    }
}