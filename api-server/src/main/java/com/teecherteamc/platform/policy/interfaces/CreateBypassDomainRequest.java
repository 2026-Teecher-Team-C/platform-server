package com.teecherteamc.platform.policy.interfaces;

import jakarta.validation.constraints.NotBlank;

/**
 * 바이패스 도메인 등록 요청 본문. (POST /api/admin/bypass-domains)
 *
 *   { "host": "update.vendor.com", "category": "PINNED", "reason": "인증서 피닝" }
 *
 * 여기서는 "값이 비어 있는지"만 본다.
 * host 형식과 category 값 검증은 도메인의 BypassHost·BypassCategory가 하고,
 * 틀리면 컨트롤러가 400으로 바꾼다.
 */
public record CreateBypassDomainRequest(

        // 필드 이름이 JSON 키가 된다. 목록 응답(BypassDomainItem)의 이름과 같다.
        @NotBlank(message = "host는 필수입니다.")
        String host,

        @NotBlank(message = "category는 필수입니다.")
        String category,

        @NotBlank(message = "reason은 필수입니다.")
        String reason
) {
}
