package com.teecherteamc.platform.hashlist.interfaces;

import jakarta.validation.constraints.NotBlank;

/**
 * 블랙리스트 등록 요청 본문. (POST /api/admin/blacklist)
 *
 *   { "sha256": "275a021b...", "reason": "EICAR 테스트 파일" }
 *
 * severity는 요청에 없다. 서버가 기본값 HIGH로 넣는다. (콘솔의 createBlacklist()도 보내지 않는다)
 *
 * 여기서는 "값이 비어 있는지"만 본다.
 * SHA-256 형식(64자리 16진수) 검증은 도메인의 HashKey가 하고,
 * 틀리면 컨트롤러가 400으로 바꾼다.
 */
public record CreateBlacklistRequest(

        // 필드 이름이 JSON 키가 된다. 콘솔이 보내는 이름과 같아야 한다.
        @NotBlank(message = "sha256은 필수입니다.")
        String sha256,

        @NotBlank(message = "reason은 필수입니다.")
        String reason
) {
}