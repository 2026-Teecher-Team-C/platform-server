package com.teecherteamc.platform.hashlist.interfaces;

import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.hashlist.application.RemoveWhitelistEntryUseCase;
import com.teecherteamc.platform.hashlist.application.WhitelistNotFoundException;
import com.teecherteamc.platform.hashlist.domain.InvalidHashException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화이트리스트 관리 REST 엔드포인트.
 *
 * 로그인(access 토큰)이 필요하다. SecurityConfig가 막는다. (docs/auth-policy.md)
 * 역할(ANALYST 이상) 구분은 아직 하지 않는다.
 */
@RestController
public class WhitelistController {

    private final RemoveWhitelistEntryUseCase removeWhitelistEntryUseCase;

    public WhitelistController(RemoveWhitelistEntryUseCase removeWhitelistEntryUseCase) {
        this.removeWhitelistEntryUseCase = removeWhitelistEntryUseCase;
    }

    /** 화이트리스트 항목을 해제(비활성화)한다. 행은 남는다. */
    @DeleteMapping("/api/admin/whitelist/{sha256}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AdminPrincipal principal,
                       @PathVariable String sha256, HttpServletRequest httpRequest) {
        try {
            removeWhitelistEntryUseCase.remove(sha256, principal.adminId(), ClientIp.resolve(httpRequest));
        } catch (InvalidHashException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (WhitelistNotFoundException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, e.getMessage());
        }
    }
}
