package com.teecherteamc.platform.hashlist.interfaces;

import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.hashlist.application.AddWhitelistEntryUseCase;
import com.teecherteamc.platform.hashlist.application.HashListConflictException;
import com.teecherteamc.platform.hashlist.domain.InvalidHashException;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private final AddWhitelistEntryUseCase addWhitelistEntryUseCase;

    public WhitelistController(AddWhitelistEntryUseCase addWhitelistEntryUseCase) {
        this.addWhitelistEntryUseCase = addWhitelistEntryUseCase;
    }

    /** 해시를 화이트리스트에 등록한다. 등록 즉시 그 파일은 검사 없이 통과된다. */
    @PostMapping("/api/admin/whitelist")
    @ResponseStatus(HttpStatus.CREATED)
    public WhitelistItem create(@AuthenticationPrincipal AdminPrincipal principal,
                                @Valid @RequestBody CreateWhitelistRequest request,
                                HttpServletRequest httpRequest) {
        try {
            WhitelistEntry entry = addWhitelistEntryUseCase.add(new AddWhitelistEntryUseCase.Command(
                    request.sha256(), request.reason(), request.expiresAt(), principal.adminId(),
                    ClientIp.resolve(httpRequest)));
            // 방금 등록한 사람이 로그인한 관리자라 username을 다시 조회하지 않는다.
            return WhitelistItem.from(entry, principal.username());
        } catch (InvalidHashException | IllegalArgumentException e) {
            // SHA-256 형식이 틀렸거나 만료 시각이 과거 → 400
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (HashListConflictException e) {
            // 블랙리스트에 있거나 이미 화이트리스트에 있음 → 409
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
    }
}
