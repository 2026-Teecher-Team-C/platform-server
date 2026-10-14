package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.event.application.ListDownloadEventsUseCase;
import com.teecherteamc.platform.event.domain.DownloadEventListRow;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 콘솔의 다운로드 이벤트 목록 REST 엔드포인트. 데모 범위라 인증 없음 — 인증 미들웨어가 아직
 * 없는 상태에서 명시적으로 생략하기로 합의됐다(2026-10-14 요구사항).
 *
 * <p>cursor 파라미터는 받되 무시한다: "읽기 쿼리 하나" 요구사항상 실제 다중 페이지 커서 인코딩/디코딩은
 * 만들지 않고 항상 nextCursor=null을 반환한다 — limit개까지 최신순으로 한 번에 긁어온다.
 */
@RestController
public class DownloadEventController {

    private static final Set<String> VALID_DECISIONS =
            Set.of("RELEASED", "BLOCKED", "BYPASSED", "FAIL_CLOSE");
    private static final int MAX_LIMIT = 200;

    private final ListDownloadEventsUseCase listDownloadEventsUseCase;

    public DownloadEventController(ListDownloadEventsUseCase listDownloadEventsUseCase) {
        this.listDownloadEventsUseCase = listDownloadEventsUseCase;
    }

    @GetMapping("/api/admin/download-events")
    public ResponseEntity<?> list(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        UUID parsedAgentId = null;
        if (agentId != null) {
            try {
                parsedAgentId = UUID.fromString(agentId);
            } catch (IllegalArgumentException e) {
                return badRequest("agentId must be a valid UUID");
            }
        }

        if (decision != null && !VALID_DECISIONS.contains(decision)) {
            return badRequest("decision must be one of " + VALID_DECISIONS);
        }

        Instant parsedFrom = parseInstantOrNull(from);
        if (from != null && parsedFrom == null) {
            return badRequest("from must be an ISO-8601 timestamp");
        }
        Instant parsedTo = parseInstantOrNull(to);
        if (to != null && parsedTo == null) {
            return badRequest("to must be an ISO-8601 timestamp");
        }

        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            return badRequest("limit must be between 1 and " + MAX_LIMIT);
        }

        List<DownloadEventListRow> rows =
                listDownloadEventsUseCase.list(parsedAgentId, decision, parsedFrom, parsedTo, limit);

        List<DownloadEventListItem> items = rows.stream().map(DownloadEventListItem::from).toList();
        return ResponseEntity.ok(new CursorPage<>(items, null));
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ErrorBody(message));
    }

    private record ErrorBody(String message) {
    }
}
