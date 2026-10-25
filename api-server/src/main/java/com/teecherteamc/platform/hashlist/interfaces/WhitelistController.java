package com.teecherteamc.platform.hashlist.interfaces;

import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.hashlist.application.AddWhitelistEntryUseCase;
import com.teecherteamc.platform.hashlist.application.HashListConflictException;
import com.teecherteamc.platform.hashlist.application.InvalidExpiryException;
import com.teecherteamc.platform.hashlist.application.ListWhitelistUseCase;
import com.teecherteamc.platform.hashlist.application.RemoveWhitelistEntryUseCase;
import com.teecherteamc.platform.hashlist.application.UpdateWhitelistReasonUseCase;
import com.teecherteamc.platform.hashlist.application.WhitelistNotFoundException;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.ListWhitelistPort;
import com.teecherteamc.platform.hashlist.domain.InvalidHashException;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    private static final Set<String> VALID_ORIGINS = Set.of("RESTORED", "PREREGISTERED");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListWhitelistUseCase listWhitelistUseCase;
    private final AddWhitelistEntryUseCase addWhitelistEntryUseCase;
    private final RemoveWhitelistEntryUseCase removeWhitelistEntryUseCase;
    private final UpdateWhitelistReasonUseCase updateWhitelistReasonUseCase;
    private final ListWhitelistPort listWhitelistPort;

    public WhitelistController(ListWhitelistUseCase listWhitelistUseCase,
                               AddWhitelistEntryUseCase addWhitelistEntryUseCase,
                               RemoveWhitelistEntryUseCase removeWhitelistEntryUseCase,
                               UpdateWhitelistReasonUseCase updateWhitelistReasonUseCase,
                               ListWhitelistPort listWhitelistPort) {
        this.listWhitelistUseCase = listWhitelistUseCase;
        this.addWhitelistEntryUseCase = addWhitelistEntryUseCase;
        this.removeWhitelistEntryUseCase = removeWhitelistEntryUseCase;
        this.updateWhitelistReasonUseCase = updateWhitelistReasonUseCase;
        this.listWhitelistPort = listWhitelistPort;
    }

    /**
     * 화이트리스트 목록. 기본은 활성이면서 만료되지 않은 항목만 최신순으로 내려준다.
     * hashValue는 앞부분 일치(대소문자 무관)로 찾는다.
     */
    @GetMapping("/api/admin/whitelist")
    public PageResponse<WhitelistItem> list(
            @RequestParam(required = false) String hashValue,
            @RequestParam(required = false) String origin,
            @RequestParam(defaultValue = "true") boolean isActive,
            @RequestParam(defaultValue = "false") boolean includeExpired,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        if (origin != null && !VALID_ORIGINS.contains(origin)) {
            throw invalid("origin must be one of " + VALID_ORIGINS);
        }
        if (page < 0) {
            throw invalid("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }
        String hashPrefix = hashValue == null || hashValue.isBlank()
                ? null : hashValue.strip().toLowerCase(Locale.ROOT);

        return PageResponse.from(
                listWhitelistUseCase.list(hashPrefix, origin, isActive, includeExpired, page, size),
                WhitelistItem::from);
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
        } catch (InvalidHashException | InvalidExpiryException e) {
            // SHA-256 형식이 틀렸거나 만료 시각이 과거 → 400
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (HashListConflictException e) {
            // 블랙리스트에 있거나 이미 화이트리스트에 있음 → 409
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
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

    /** 등록 사유만 고친다. 다른 필드는 그대로다. */
    @PatchMapping("/api/admin/whitelist/{sha256}")
    public WhitelistItem updateReason(@AuthenticationPrincipal AdminPrincipal principal,
                                      @PathVariable String sha256,
                                      @Valid @RequestBody UpdateWhitelistReasonRequest request,
                                      HttpServletRequest httpRequest) {
        HashKey key;
        try {
            key = updateWhitelistReasonUseCase.update(sha256, request.reason(), principal.adminId(),
                    ClientIp.resolve(httpRequest));
        } catch (InvalidHashException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (WhitelistNotFoundException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, e.getMessage());
        }
        // 등록자가 수정한 사람과 다를 수 있어서 username은 admin_users 조인으로 다시 읽는다.
        return listWhitelistPort.find(key)
                .map(WhitelistItem::from)
                .orElseThrow(() -> new IllegalStateException("방금 수정한 화이트리스트 항목이 없습니다: " + key.getHashValue()));
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message);
    }
}
