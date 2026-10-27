package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.quarantine.application.FindQuarantineFileUseCase;
import com.teecherteamc.platform.quarantine.application.ListQuarantineFilesUseCase;
import com.teecherteamc.platform.quarantine.application.RestoreQuarantineFileUseCase;
import com.teecherteamc.platform.quarantine.domain.QuarantineDetailRow;
import com.teecherteamc.platform.quarantine.domain.QuarantineNotRestorableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 콘솔의 격리 파일 목록/상세/복원 REST 엔드포인트. 목록/상세는 데모 범위라 인증 없음 —
 * 다운로드 이벤트 목록과 같은 이유로 인증 미들웨어가 아직 없는 상태에서 명시적으로 생략하기로 합의됐다.
 * 복원은 이후 들어온 로그인 인프라를 써서 로그인한 관리자만 허용한다(역할 구분은 아직 없음).
 *
 * <p>응답에 S3 저장 위치(bucket/key)는 절대 포함하지 않는다 — 콘솔을 거치지 않고 격리 저장소에
 * 직접 접근하는 경로가 드러나면 안 된다는 요구사항.
 */
@RestController
public class QuarantineController {

    private static final Set<String> VALID_STATUSES =
            Set.of("QUARANTINED", "RESTORED", "DELETED", "EXPIRED");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListQuarantineFilesUseCase listQuarantineFilesUseCase;
    private final FindQuarantineFileUseCase findQuarantineFileUseCase;
    private final RestoreQuarantineFileUseCase restoreQuarantineFileUseCase;

    public QuarantineController(
            ListQuarantineFilesUseCase listQuarantineFilesUseCase,
            FindQuarantineFileUseCase findQuarantineFileUseCase,
            RestoreQuarantineFileUseCase restoreQuarantineFileUseCase) {
        this.listQuarantineFilesUseCase = listQuarantineFilesUseCase;
        this.findQuarantineFileUseCase = findQuarantineFileUseCase;
        this.restoreQuarantineFileUseCase = restoreQuarantineFileUseCase;
    }

    @GetMapping("/api/admin/quarantine")
    public PageResponse<QuarantineListItem> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String agentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        if (status != null && !VALID_STATUSES.contains(status)) {
            throw invalid("status must be one of " + VALID_STATUSES);
        }
        UUID parsedAgentId = parseUuidOrNull(agentId);
        if (page < 0) {
            throw invalid("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }

        return PageResponse.from(
                listQuarantineFilesUseCase.list(status, parsedAgentId, page, size),
                QuarantineListItem::from);
    }

    @GetMapping("/api/admin/quarantine/{quarantineId}")
    public QuarantineDetail detail(@PathVariable String quarantineId) {
        UUID parsedQuarantineId = parseUuid(quarantineId, "quarantineId");
        QuarantineDetailRow row = findQuarantineFileUseCase.find(parsedQuarantineId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "quarantine file not found: " + quarantineId));
        return QuarantineDetail.from(row);
    }

    /** 격리된 파일을 오탐으로 판단해 복원한다. 복원 즉시 해당 해시가 화이트리스트로 통과된다. */
    @PostMapping("/api/admin/quarantine/{quarantineId}/restore")
    public RestoreQuarantineResponse restore(
            @PathVariable String quarantineId,
            @RequestBody(required = false) RestoreQuarantineRequest request,
            @AuthenticationPrincipal AdminPrincipal principal,
            HttpServletRequest httpRequest) {
        UUID parsedQuarantineId = parseUuid(quarantineId, "quarantineId");
        String reason = (request == null || request.reason() == null || request.reason().isBlank())
                ? "오탐 복원" : request.reason();
        try {
            return restoreQuarantineFileUseCase
                    .restore(parsedQuarantineId, reason, principal.adminId(), ClientIp.resolve(httpRequest))
                    .map(RestoreQuarantineResponse::from)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.NOT_FOUND, "quarantine file not found: " + quarantineId));
        } catch (QuarantineNotRestorableException e) {
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
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

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message);
    }
}
