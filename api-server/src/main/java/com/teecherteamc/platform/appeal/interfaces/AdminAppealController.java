package com.teecherteamc.platform.appeal.interfaces;

import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.appeal.application.AdminAppealDetail;
import com.teecherteamc.platform.appeal.application.AdminAppealListItem;
import com.teecherteamc.platform.appeal.application.AppealAlreadyDecidedException;
import com.teecherteamc.platform.appeal.application.AppealNotFoundException;
import com.teecherteamc.platform.appeal.application.DecideAppealUseCase;
import com.teecherteamc.platform.appeal.application.GetAppealDetailUseCase;
import com.teecherteamc.platform.appeal.application.InvalidDecisionNoteException;
import com.teecherteamc.platform.appeal.application.ListAppealsUseCase;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 콘솔의 소명 목록·상세 조회(ADMIN·ANALYST)와 승인·거절(ADMIN). 승인·거절 POST는 SecurityConfig의
 * 맨 끝 anyRequest().hasRole(ADMIN)에 걸린다.
 */
@RestController
public class AdminAppealController {

    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListAppealsUseCase listAppealsUseCase;
    private final GetAppealDetailUseCase getAppealDetailUseCase;
    private final DecideAppealUseCase decideAppealUseCase;

    public AdminAppealController(ListAppealsUseCase listAppealsUseCase,
                                 GetAppealDetailUseCase getAppealDetailUseCase,
                                 DecideAppealUseCase decideAppealUseCase) {
        this.listAppealsUseCase = listAppealsUseCase;
        this.getAppealDetailUseCase = getAppealDetailUseCase;
        this.decideAppealUseCase = decideAppealUseCase;
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
        return getAppealDetailUseCase.get(parseId(appealId))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "appeal not found: " + appealId));
    }

    /** 승인한다. 그 해시가 화이트리스트에 들어가 회사 모든 PC에서 통과한다. 의견은 선택. */
    @PostMapping("/api/admin/appeals/{appealId}/approve")
    public AdminAppealDetail approve(@PathVariable String appealId,
                                     @RequestBody(required = false) DecideAppealRequest request,
                                     @AuthenticationPrincipal AdminPrincipal principal,
                                     HttpServletRequest httpRequest) {
        UUID id = parseId(appealId);
        return decide(appealId, () -> decideAppealUseCase.approve(id, noteOf(request), principal.adminId(),
                ClientIp.resolve(httpRequest)));
    }

    /** 거절한다. 의견은 필수다. */
    @PostMapping("/api/admin/appeals/{appealId}/reject")
    public AdminAppealDetail reject(@PathVariable String appealId,
                                    @RequestBody(required = false) DecideAppealRequest request,
                                    @AuthenticationPrincipal AdminPrincipal principal,
                                    HttpServletRequest httpRequest) {
        UUID id = parseId(appealId);
        return decide(appealId, () -> decideAppealUseCase.reject(id, noteOf(request), principal.adminId(),
                ClientIp.resolve(httpRequest)));
    }

    private static AdminAppealDetail decide(String appealId, Supplier<AdminAppealDetail> action) {
        try {
            return action.get();
        } catch (AppealNotFoundException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "appeal not found: " + appealId);
        } catch (InvalidDecisionNoteException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (AppealAlreadyDecidedException e) {
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
    }

    private static String noteOf(DecideAppealRequest request) {
        return request == null ? null : request.note();
    }

    private static UUID parseId(String appealId) {
        try {
            return UUID.fromString(appealId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "appealId must be a UUID");
        }
    }
}
