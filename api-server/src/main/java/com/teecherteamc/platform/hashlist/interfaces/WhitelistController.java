package com.teecherteamc.platform.hashlist.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.hashlist.application.ListWhitelistUseCase;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    // 해시 앞부분. 소문자 hex만 허용해서 SQL LIKE에 넘길 때 %, _ 가 섞이지 않게 한다.
    private static final Pattern HASH_PREFIX = Pattern.compile("^[0-9a-f]{1,64}$");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private final ListWhitelistUseCase listWhitelistUseCase;

    public WhitelistController(ListWhitelistUseCase listWhitelistUseCase) {
        this.listWhitelistUseCase = listWhitelistUseCase;
    }

    /**
     * 화이트리스트 목록. 기본은 활성이면서 만료되지 않은 항목만 최신순으로 내려준다.
     * hashValue는 앞부분 일치(대소문자 무관)로 찾는다. 빈 문자열 필터는 걸지 않은 것으로 본다.
     */
    @GetMapping("/api/admin/whitelist")
    public PageResponse<WhitelistItem> list(
            @RequestParam(required = false) String hashValue,
            @RequestParam(required = false) String origin,
            @RequestParam(defaultValue = "true") boolean isActive,
            @RequestParam(defaultValue = "false") boolean includeExpired,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        String hashPrefix = blankToNull(hashValue);
        if (hashPrefix != null) {
            hashPrefix = hashPrefix.toLowerCase(Locale.ROOT);
            if (!HASH_PREFIX.matcher(hashPrefix).matches()) {
                throw invalid("hashValue must be 1 to 64 hexadecimal characters");
            }
        }
        String originFilter = blankToNull(origin);
        if (originFilter != null && !VALID_ORIGINS.contains(originFilter)) {
            throw invalid("origin must be one of " + VALID_ORIGINS);
        }
        if (page < 0) {
            throw invalid("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }

        return PageResponse.from(
                listWhitelistUseCase.list(hashPrefix, originFilter, isActive, includeExpired, page, size),
                WhitelistItem::from);
    }

    // 빈 문자열(?origin=)은 필터를 안 건 것으로 본다.
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message);
    }
}
