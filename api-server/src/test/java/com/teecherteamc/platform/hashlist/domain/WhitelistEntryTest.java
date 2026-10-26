package com.teecherteamc.platform.hashlist.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WhitelistEntryTest {

    private static final HashKey KEY = HashKey.sha256("a".repeat(64));
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");

    private static WhitelistEntry restoredEntry() {
        return WhitelistEntry.register(KEY, "복원", UUID.randomUUID(), UUID.randomUUID(), null, CREATED_AT);
    }

    @Test
    void 사유를_바꾸면_다른_필드는_그대로다() {
        WhitelistEntry entry = restoredEntry();
        UUID addedBy = entry.getAddedBy();
        UUID origin = entry.getOriginQuarantineId();

        entry.changeReason("사내 배포 도구 v2");

        assertThat(entry.getReason()).isEqualTo("사내 배포 도구 v2");
        assertThat(entry.getAddedBy()).isEqualTo(addedBy);
        assertThat(entry.getOriginQuarantineId()).isEqualTo(origin);
        assertThat(entry.isActive()).isTrue();
    }

    @Test
    void 빈_사유로_바꾸면_IllegalArgumentException() {
        WhitelistEntry entry = restoredEntry();

        assertThatThrownBy(() -> entry.changeReason(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
