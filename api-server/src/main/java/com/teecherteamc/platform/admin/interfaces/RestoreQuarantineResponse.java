package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.quarantine.application.RestoreQuarantineFileUseCase;
import java.time.Instant;
import java.util.UUID;

/** console/src/lib/api/quarantine.ts의 RestoreQuarantineResponse와 1:1 대응하는 응답 DTO. */
public record RestoreQuarantineResponse(
        UUID quarantineId, String sha256, String status, boolean whitelisted, Instant restoredAt) {

    static RestoreQuarantineResponse from(RestoreQuarantineFileUseCase.Result result) {
        return new RestoreQuarantineResponse(
                result.quarantineId(), result.sha256(), result.status(), result.whitelisted(), result.restoredAt());
    }
}
