package com.teecherteamc.platform.dashboard.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.dashboard.application.ListUnacknowledgedBlocksUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 대시보드 종 알림: 확인 안 한 높은 심각도(HIGH·CRITICAL) 차단. 기간 필터 없이 최신순이다. */
@RestController
public class UnacknowledgedBlocksController {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListUnacknowledgedBlocksUseCase useCase;

    public UnacknowledgedBlocksController(ListUnacknowledgedBlocksUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/api/admin/dashboard/unacknowledged-blocks")
    public PageResponse<UnacknowledgedBlockItem> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        if (page < 0) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "size must be between 1 and " + MAX_SIZE);
        }
        return PageResponse.from(useCase.list(page, size), UnacknowledgedBlockItem::from);
    }
}
