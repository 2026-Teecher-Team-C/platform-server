package com.teecherteamc.platform.hashlist.interfaces;

import jakarta.validation.constraints.NotBlank;

/** 화이트리스트 사유 수정 요청 본문. (PATCH /api/admin/whitelist/{sha256})  { "reason": "..." } */
public record UpdateWhitelistReasonRequest(

        @NotBlank(message = "reason은 필수입니다.")
        String reason
) {
}
