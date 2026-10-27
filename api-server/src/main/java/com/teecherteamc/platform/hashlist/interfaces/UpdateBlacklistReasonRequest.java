package com.teecherteamc.platform.hashlist.interfaces;

import jakarta.validation.constraints.NotBlank;

/**
 * 블랙리스트 사유 수정 요청 본문. PATCH /api/admin/blacklist/{sha256}
 *
 * 바꿀 수 있는 건 사유뿐이라 필드가 reason 하나다.
 * 해시(sha256)는 본문이 아니라 URL 경로로 받는다.
 */
public record UpdateBlacklistReasonRequest(
        // null, 빈 문자열, 공백만 있는 문자열을 모두 막는다. 틀리면 400(INVALID_INPUT)이고 fieldErrors에 reason이 담긴다.
        @NotBlank(message = "사유는 필수입니다.") String reason) {
}