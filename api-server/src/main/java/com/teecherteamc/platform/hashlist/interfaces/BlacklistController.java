package com.teecherteamc.platform.hashlist.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.hashlist.application.AddBlacklistEntryUseCase;
import com.teecherteamc.platform.hashlist.application.HashListConflictException;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.InvalidHashException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블랙리스트 관리 REST 엔드포인트.
 *
 * 지금은 인증이 없다. 인증은 마무리 단계에서 붙이기로 했다. (docs/auth-policy.md)
 * 붙이면 이 클래스에서 로그인한 admin의 id를 꺼내 Command의 addedBy에 넘기면 된다.
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
    public BlacklistItem create(@Valid @RequestBody CreateBlacklistRequest request) {
        try {
            // addedBy는 로그인이 없어서 null (hash_blacklist.added_by가 nullable)
            BlacklistEntry entry = addBlacklistEntryUseCase.add(
                    new AddBlacklistEntryUseCase.Command(request.sha256(), request.reason(), null));
            return BlacklistItem.from(entry);
        } catch (InvalidHashException e) {
            // SHA-256 형식이 틀림 → 400. 메시지는 HashKey가 쓴 문장을 그대로 쓴다.
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (HashListConflictException e) {
            // 화이트리스트에 있거나 이미 블랙리스트에 있음 → 409
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
    }
}