package com.teecherteamc.platform.hashlist.interfaces;

import com.teecherteamc.platform.hashlist.application.ImportBlacklistBatchUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 블랙리스트 일괄 가져오기 요청. (POST /api/admin/blacklist/import)
 *
 *   { "source": "MalwareBazaar", "entries": [ { "sha256": "...", "tlsh": "T1...", "label": "Mirai" } ] }
 *
 * 항목 하나하나의 형식은 여기서 막지 않는다. 잘못된 항목은 유스케이스가 세고 건너뛴다.
 */
public record ImportBlacklistRequest(
        @NotBlank(message = "source는 필수입니다.")
        @Size(max = 64, message = "source는 64자 이하여야 합니다.")
        String source,
        @NotNull(message = "entries는 필수입니다.")
        @Size(min = 1, max = ImportBlacklistBatchUseCase.MAX_ENTRIES, message = "entries는 1~1000건이어야 합니다.")
        List<Entry> entries
) {
    public record Entry(String sha256, String tlsh, String label) {
    }
}
