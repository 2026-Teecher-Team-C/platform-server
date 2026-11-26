package com.teecherteamc.platform.appeal.interfaces;

import com.teecherteamc.platform.appeal.application.AdminAppealDetail;
import com.teecherteamc.platform.appeal.application.AdminAppealListItem;
import com.teecherteamc.platform.appeal.application.GetAppealDetailUseCase;
import com.teecherteamc.platform.appeal.application.ListAppealsUseCase;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 콘솔의 소명 목록·상세 조회(ADMIN·ANALYST). */
@RestController
public class AdminAppealController {

    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListAppealsUseCase listAppealsUseCase;
    private final GetAppealDetailUseCase getAppealDetailUseCase;

    public AdminAppealController(ListAppealsUseCase listAppealsUseCase,
                                 GetAppealDetailUseCase getAppealDetailUseCase) {
        this.listAppealsUseCase = listAppealsUseCase;
        this.getAppealDetailUseCase = getAppealDetailUseCase;
    }

    @GetMapping("/api/admin/appeals")
    public PageResponse<AdminAppealListItem> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        if (status != null && !VALID_STATUSES.contains(status)) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "status must be one of " + VALID_STATUSES);
        }
        if (page < 0) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "size must be between 1 and " + MAX_SIZE);
        }
        return PageResponse.from(listAppealsUseCase.list(status, page, size));
    }

    @GetMapping("/api/admin/appeals/{appealId}")
    public AdminAppealDetail detail(@PathVariable String appealId) {
        UUID id;
        try {
            id = UUID.fromString(appealId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "appealId must be a UUID");
        }
        return getAppealDetailUseCase.get(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "appeal not found: " + appealId));
    }
}
