package com.teecherteamc.platform.audit.interfaces;

import com.teecherteamc.platform.audit.application.ListAuditLogsUseCase;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.audit.domain.ListAuditLogsPort;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * 감사 로그 조회 REST 엔드포인트. 이 API는 audit_logs에 행을 만들지 않는다.
 *
 * 로그인(access 토큰)이 필요하다. SecurityConfig가 막는다. (docs/auth-policy.md)
 * 명세상 ADMIN 전용이지만 역할 구분은 아직 하지 않는다. (다른 관리 API와 같다)
 */
@RestController
public class AuditLogController {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListAuditLogsUseCase listAuditLogsUseCase;
    private final JsonMapper jsonMapper;

    public AuditLogController(ListAuditLogsUseCase listAuditLogsUseCase, JsonMapper jsonMapper) {
        this.listAuditLogsUseCase = listAuditLogsUseCase;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 감사 로그 목록. 최신순으로 내려준다. 빈 문자열 필터는 걸지 않은 것으로 본다.
     * 기간은 from 이상, to 미만이다.
     */
    @GetMapping("/api/admin/audit-logs")
    public PageResponse<AuditLogItem> list(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean dangerousOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        UUID parsedActorId = parseUuidOrNull(blankToNull(actorId));
        String actionFilter = enumNameOrNull(AuditAction.class, blankToNull(action), "action");
        String targetTypeFilter = enumNameOrNull(AuditTargetType.class, blankToNull(targetType), "targetType");
        Instant parsedFrom = parseInstantOrNull(blankToNull(from), "from");
        Instant parsedTo = parseInstantOrNull(blankToNull(to), "to");
        if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
            throw invalid("from must not be after to");
        }
        if (page < 0) {
            throw invalid("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }

        ListAuditLogsPort.Filter filter = new ListAuditLogsPort.Filter(
                parsedActorId, actionFilter, targetTypeFilter, parsedFrom, parsedTo, dangerousOnly);
        return PageResponse.from(listAuditLogsUseCase.list(filter, page, size),
                row -> AuditLogItem.from(row, jsonMapper));
    }

    // 빈 문자열(?action=)은 필터를 안 건 것으로 본다.
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalid("actorId must be a valid UUID");
        }
    }

    // 정해진 값만 받는다. 오타 필터가 조용히 빈 목록을 내려주면 "기록이 없다"로 오해한다.
    private static <E extends Enum<E>> String enumNameOrNull(Class<E> type, String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value).name();
        } catch (IllegalArgumentException e) {
            throw invalid(name + " is not a known value: " + value);
        }
    }

    private static Instant parseInstantOrNull(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(name + " must be an ISO-8601 timestamp");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message);
    }
}
