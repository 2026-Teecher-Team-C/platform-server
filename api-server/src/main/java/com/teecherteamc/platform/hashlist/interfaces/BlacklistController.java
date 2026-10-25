package com.teecherteamc.platform.hashlist.interfaces;

import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.hashlist.application.AddBlacklistEntryUseCase;
import com.teecherteamc.platform.hashlist.application.HashListConflictException;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.InvalidHashException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블랙리스트 관리 REST 엔드포인트.
 *
 * 인증은 SecurityConfig가 걸고, 여기서는 로그인한 관리자를 AdminPrincipal로 받는다.
 * (AUTH_ENABLED=false일 때는 초기 관리자가 같은 모양으로 들어온다)
 */
@RestController
public class BlacklistController {

    private final AddBlacklistEntryUseCase addBlacklistEntryUseCase;

    public BlacklistController(AddBlacklistEntryUseCase addBlacklistEntryUseCase) {
        this.addBlacklistEntryUseCase = addBlacklistEntryUseCase;
    }

    /** 알려진 악성 해시를 블랙리스트에 등록한다. 등록 즉시 그 파일은 차단된다. */
    @PostMapping("/api/admin/blacklist")
    @ResponseStatus(HttpStatus.CREATED)   // 성공 시 200이 아니라 201
    public BlacklistItem create(@Valid @RequestBody CreateBlacklistRequest request,
                                @AuthenticationPrincipal AdminPrincipal principal,
                                HttpServletRequest httpRequest) {
        try {
            // addedBy: 로그인한 관리자 / ip: 감사 로그에 남길 클라이언트 IP (X-Forwarded-For의 첫 주소)
            BlacklistEntry entry = addBlacklistEntryUseCase.add(new AddBlacklistEntryUseCase.Command(
                    request.sha256(), request.reason(), principal.adminId(), ClientIp.resolve(httpRequest)));
            return BlacklistItem.from(entry, principal.username());
        } catch (InvalidHashException e) {
            // SHA-256 형식이 틀림 → 400. 메시지는 HashKey가 쓴 문장을 그대로 쓴다.
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (HashListConflictException e) {
            // 화이트리스트에 있거나 이미 블랙리스트에 있음 → 409
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
    }
}