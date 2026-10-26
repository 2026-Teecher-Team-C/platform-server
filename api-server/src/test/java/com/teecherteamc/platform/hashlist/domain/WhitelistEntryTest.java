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
    void 비활성_항목을_되살리면_사유_등록자_만료가_바뀌고_복원_출처는_비워진다() {
        WhitelistEntry entry = restoredEntry();
        entry.deactivate();
        UUID newAdmin = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2027-01-01T00:00:00Z");

        entry.reactivate("사내 배포 도구", newAdmin, expiresAt);

        assertThat(entry.isActive()).isTrue();
        assertThat(entry.getReason()).isEqualTo("사내 배포 도구");
        assertThat(entry.getAddedBy()).isEqualTo(newAdmin);
        assertThat(entry.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(entry.getOriginQuarantineId()).isNull();
        assertThat(entry.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void 활성_항목을_되살리면_IllegalStateException() {
        WhitelistEntry entry = restoredEntry();

        assertThatThrownBy(() -> entry.reactivate("다시", UUID.randomUUID(), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 빈_사유로_되살리면_IllegalArgumentException() {
        WhitelistEntry entry = restoredEntry();
        entry.deactivate();

        assertThatThrownBy(() -> entry.reactivate(" ", UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
