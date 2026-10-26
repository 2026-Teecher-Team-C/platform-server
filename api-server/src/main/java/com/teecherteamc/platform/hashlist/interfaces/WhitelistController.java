package com.teecherteamc.platform.hashlist.interfaces;

import com.teecherteamc.platform.admin.domain.AdminUser;
import com.teecherteamc.platform.admin.domain.AdminUserRepository;
import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.hashlist.application.UpdateWhitelistReasonUseCase;
import com.teecherteamc.platform.hashlist.application.WhitelistNotFoundException;
import com.teecherteamc.platform.hashlist.domain.InvalidHashException;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화이트리스트 관리 REST 엔드포인트.
 *
 * 로그인(access 토큰)이 필요하다. SecurityConfig가 막는다. (docs/auth-policy.md)
 * 역할(ANALYST 이상) 구분은 아직 하지 않는다.
 */
@RestController
public class WhitelistController {

    private final UpdateWhitelistReasonUseCase updateWhitelistReasonUseCase;
    private final AdminUserRepository adminUserRepository;

    public WhitelistController(UpdateWhitelistReasonUseCase updateWhitelistReasonUseCase,
                               AdminUserRepository adminUserRepository) {
        this.updateWhitelistReasonUseCase = updateWhitelistReasonUseCase;
        this.adminUserRepository = adminUserRepository;
    }

    /** 등록 사유만 고친다. 다른 필드는 그대로다. */
    @PatchMapping("/api/admin/whitelist/{sha256}")
    public WhitelistItem updateReason(@AuthenticationPrincipal AdminPrincipal principal,
                                      @PathVariable String sha256,
                                      @Valid @RequestBody UpdateWhitelistReasonRequest request,
                                      HttpServletRequest httpRequest) {
        WhitelistEntry entry;
        try {
            entry = updateWhitelistReasonUseCase.update(sha256, request.reason(), principal.adminId(),
                    ClientIp.resolve(httpRequest));
        } catch (InvalidHashException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (WhitelistNotFoundException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, e.getMessage());
        }
        // 등록자가 수정한 사람과 다를 수 있어서 username은 등록자 id로 다시 읽는다.
        String addedByUsername = adminUserRepository.findById(entry.getAddedBy())
                .map(AdminUser::getUsername)
                .orElse(null);
        return WhitelistItem.from(entry, addedByUsername);
    }
}
