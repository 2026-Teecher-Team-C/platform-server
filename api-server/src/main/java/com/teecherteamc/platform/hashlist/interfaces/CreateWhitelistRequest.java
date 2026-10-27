package com.teecherteamc.platform.hashlist.interfaces;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * 화이트리스트 등록 요청 본문. (POST /api/admin/whitelist)
 *
 *   { "sha256": "44d88612...", "reason": "사내 배포 도구", "expiresAt": null }
 *
 * expiresAt은 선택이다. 없으면 무기한. SHA-256 형식 검증은 도메인의 HashKey가 한다.
 */
public record CreateWhitelistRequest(

        @NotBlank(message = "sha256은 필수입니다.")
        String sha256,

        @NotBlank(message = "reason은 필수입니다.")
        String reason,

        Instant expiresAt
) {
}
