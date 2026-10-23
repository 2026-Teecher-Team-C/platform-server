package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.quarantine.application.ListQuarantineFilesUseCase;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 콘솔의 격리 파일 목록 REST 엔드포인트. 데모 범위라 인증 없음 — 다운로드 이벤트 목록과 같은
 * 이유로 인증 미들웨어가 아직 없는 상태에서 명시적으로 생략하기로 합의됐다.
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

    public QuarantineController(ListQuarantineFilesUseCase listQuarantineFilesUseCase) {
        this.listQuarantineFilesUseCase = listQuarantineFilesUseCase;
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

    private static UUID parseUuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalid("agentId must be a valid UUID");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message);
    }
}
