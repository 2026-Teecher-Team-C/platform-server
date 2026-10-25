package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.event.application.FindDownloadEventUseCase;
import com.teecherteamc.platform.event.application.ListDownloadEventsUseCase;
import com.teecherteamc.platform.event.domain.DownloadEventDetailRow;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 콘솔의 다운로드 이벤트 목록/상세 REST 엔드포인트. 목록만 시연용으로 로그인 없이 열어 둔다(2026-10-14
 * 요구사항, SecurityConfig에서 목록 경로만 permitAll). 상세는 로그인이 필요하다. 시연이 끝나면 목록도 막는다
 * (docs/auth-policy.md 1장).
 *
 * <p>목록 응답은 API 컨벤션의 PageResponse(page는 0부터)로 내린다. 커서 방식(nextCursor)은
 * 2026-10-18 팀 결정으로 폐기했다.
 */
@RestController
public class DownloadEventController {

    private static final Set<String> VALID_DECISIONS =
            Set.of("RELEASED", "BLOCKED", "BYPASSED", "FAIL_CLOSE");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListDownloadEventsUseCase listDownloadEventsUseCase;
    private final FindDownloadEventUseCase findDownloadEventUseCase;

    public DownloadEventController(
            ListDownloadEventsUseCase listDownloadEventsUseCase,
            FindDownloadEventUseCase findDownloadEventUseCase) {
        this.listDownloadEventsUseCase = listDownloadEventsUseCase;
        this.findDownloadEventUseCase = findDownloadEventUseCase;
    }

    @GetMapping("/api/admin/download-events")
    public PageResponse<DownloadEventListItem> list(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        UUID parsedAgentId = parseUuidOrNull(agentId);
        if (decision != null && !VALID_DECISIONS.contains(decision)) {
            throw invalid("decision must be one of " + VALID_DECISIONS);
        }
        Instant parsedFrom = parseInstantOrNull(from, "from");
        Instant parsedTo = parseInstantOrNull(to, "to");
        if (page < 0) {
            throw invalid("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }

        return PageResponse.from(
                listDownloadEventsUseCase.list(parsedAgentId, decision, parsedFrom, parsedTo, page, size),
                DownloadEventListItem::from);
    }

    @GetMapping("/api/admin/download-events/{eventId}")
    public DownloadEventDetail detail(@PathVariable String eventId) {
        UUID parsedEventId = parseUuid(eventId, "eventId");
        DownloadEventDetailRow row = findDownloadEventUseCase.find(parsedEventId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "download event not found: " + eventId));
        return DownloadEventDetail.from(row);
    }

    private static UUID parseUuidOrNull(String value) {
        return value == null ? null : parseUuid(value, "agentId");
    }

    private static UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalid(fieldName + " must be a valid UUID");
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
