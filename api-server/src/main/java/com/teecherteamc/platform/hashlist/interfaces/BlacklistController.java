package com.teecherteamc.platform.hashlist.interfaces;

import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.hashlist.application.AddBlacklistEntryUseCase;
import com.teecherteamc.platform.hashlist.application.BlacklistEntryNotFoundException;
import com.teecherteamc.platform.hashlist.application.HashListConflictException;
import com.teecherteamc.platform.hashlist.application.ListBlacklistUseCase;
import com.teecherteamc.platform.hashlist.application.UpdateBlacklistReasonUseCase;
import com.teecherteamc.platform.hashlist.application.RemoveBlacklistEntryUseCase;
import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.InvalidHashException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
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
 * 블랙리스트 관리 REST 엔드포인트.
 *
 * 인증은 SecurityConfig가 걸고, 여기서는 로그인한 관리자를 AdminPrincipal로 받는다.
 * (AUTH_ENABLED=false일 때는 초기 관리자가 같은 모양으로 들어온다)
 *
 * 목록 응답은 API 컨벤션의 PageResponse(page는 0부터)로 내린다.
 */
@RestController
public class BlacklistController {

    private static final List<String> VALID_SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final List<String> VALID_SOURCES = List.of("MANUAL", "IMPORT");
    // 해시 앞부분. 소문자 hex만 허용해서 SQL LIKE에 넘길 때 %, _ 가 섞이지 않게 한다.
    private static final Pattern HASH_PREFIX = Pattern.compile("^[0-9a-f]{1,64}$");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final AddBlacklistEntryUseCase addBlacklistEntryUseCase;
    private final ListBlacklistUseCase listBlacklistUseCase;
    private final UpdateBlacklistReasonUseCase updateBlacklistReasonUseCase;
    private final RemoveBlacklistEntryUseCase removeBlacklistEntryUseCase;

        public BlacklistController(AddBlacklistEntryUseCase addBlacklistEntryUseCase,
                               ListBlacklistUseCase listBlacklistUseCase,
                               UpdateBlacklistReasonUseCase updateBlacklistReasonUseCase,
                             RemoveBlacklistEntryUseCase removeBlacklistEntryUseCase) {
        this.addBlacklistEntryUseCase = addBlacklistEntryUseCase;
        this.listBlacklistUseCase = listBlacklistUseCase;
        this.updateBlacklistReasonUseCase = updateBlacklistReasonUseCase;
        this.removeBlacklistEntryUseCase = removeBlacklistEntryUseCase;
    }
    

    /** 블랙리스트 목록. 생략한 필터는 걸지 않고, isActive를 생략하면 활성 항목만 준다. */
    @GetMapping("/api/admin/blacklist")
    public PageResponse<BlacklistItem> list(
            @RequestParam(required = false) String hashValue,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        String hashPrefix = blankToNull(hashValue);
        if (hashPrefix != null) {
            hashPrefix = hashPrefix.toLowerCase(Locale.ROOT);
            if (!HASH_PREFIX.matcher(hashPrefix).matches()) {
                throw invalid("hashValue must be 1 to 64 hexadecimal characters");
            }
        }
        String severityFilter = blankToNull(severity);
        if (severityFilter != null && !VALID_SEVERITIES.contains(severityFilter)) {
            throw invalid("severity must be one of " + VALID_SEVERITIES);
        }
        String sourceFilter = blankToNull(source);
        if (sourceFilter != null && !VALID_SOURCES.contains(sourceFilter)) {
            throw invalid("source must be one of " + VALID_SOURCES);
        }
        if (page < 0) {
            throw invalid("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }
        boolean active = isActive == null || isActive;   // 기본은 활성 항목만

        return PageResponse.from(
                listBlacklistUseCase.list(hashPrefix, severityFilter, sourceFilter, active, page, size),
                BlacklistItem::fromRow);
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

        /**
     * 사유 수정. 사유만 바꾸고, 바뀐 항목을 목록 조회와 같은 모양(BlacklistItem)으로 돌려준다.
     * 성공은 200이다. (새로 만든 게 아니라 고친 것이라 등록처럼 201이 아니다)
     */
    @PatchMapping("/api/admin/blacklist/{sha256}")
    public BlacklistItem updateReason(@PathVariable String sha256,
                                      @Valid @RequestBody UpdateBlacklistReasonRequest request,   // @Valid: 빈 사유는 여기서 400
                                      @AuthenticationPrincipal AdminPrincipal principal,
                                      HttpServletRequest httpRequest) {
        BlacklistEntry entry;
        try {
            entry = updateBlacklistReasonUseCase.update(new UpdateBlacklistReasonUseCase.Command(
                    sha256,
                    request.reason(),
                    principal.adminId(),                  // ← 등록 메서드에서 쓰는 방식 그대로 맞추세요
                    ClientIp.resolve(httpRequest)));
        } catch (InvalidHashException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());     // 해시 형식이 틀리면 400
        } catch (BlacklistEntryNotFoundException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, e.getMessage());         // 활성 항목이 없으면 404
        }

        // 응답용 항목은 수정이 끝나고 트랜잭션이 커밋된 뒤에 목록 조회로 다시 읽는다.
        // 등록자 username은 admin_users에서 가져와야 해서, 목록과 같은 조회를 쓰면 모양이 똑같다.
        // 이 메서드에는 @Transactional이 없어서, 위 update()가 끝나는 시점에 이미 커밋돼 있고 새 사유가 읽힌다.
        return listBlacklistUseCase
                .list(entry.getId().getHashValue(), null, null, true, 0, 1)
                .getContent().stream().findFirst()
                .map(BlacklistItem::fromRow)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "활성 상태의 블랙리스트 항목이 없습니다."));
    }
        /**
     * 해제. 행을 지우지 않고 is_active=false로 바꾼다(soft delete).
     * 성공하면 돌려줄 내용이 없어서 204(본문 없음)다.
     */
    @DeleteMapping("/api/admin/blacklist/{sha256}")
    @ResponseStatus(HttpStatus.NO_CONTENT)   // 성공 시 200이 아니라 204
    public void remove(@PathVariable String sha256,
                       @AuthenticationPrincipal AdminPrincipal principal,
                       HttpServletRequest httpRequest) {
        try {
            removeBlacklistEntryUseCase.remove(new RemoveBlacklistEntryUseCase.Command(
                    sha256, principal.adminId(), ClientIp.resolve(httpRequest)));
        } catch (InvalidHashException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());     // 해시 형식이 틀리면 400
        } catch (BlacklistEntryNotFoundException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, e.getMessage());         // 활성 항목이 없으면 404
        }
    }
    // 빈 문자열(?severity=)은 필터를 안 건 것으로 본다.
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message);
    }
}