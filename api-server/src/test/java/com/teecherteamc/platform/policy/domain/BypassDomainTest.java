package com.teecherteamc.platform.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BypassDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    private static BypassDomain newDomain() {
        return BypassDomain.register(BypassHost.of("Update.Vendor.com"), BypassCategory.PINNED, "피닝",
                UUID.randomUUID(), NOW);
    }

    @Test
    void 등록하면_활성_상태의_새_엔티티가_된다() {
        UUID admin = UUID.randomUUID();

        BypassDomain domain = BypassDomain.register(BypassHost.of("Update.Vendor.com"), BypassCategory.PINNED,
                "피닝", admin, NOW);

        assertThat(domain.getId()).isNotNull();
        assertThat(domain.isNew()).isTrue();
        assertThat(domain.getDomainPattern()).isEqualTo("update.vendor.com");
        assertThat(domain.getCategory()).isEqualTo(BypassCategory.PINNED);
        assertThat(domain.getReason()).isEqualTo("피닝");
        assertThat(domain.isActive()).isTrue();
        assertThat(domain.getCreatedBy()).isEqualTo(admin);
        assertThat(domain.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void 사유가_비어_있으면_등록할_수_없다() {
        assertThatThrownBy(() -> BypassDomain.register(BypassHost.of("a.com"), BypassCategory.PINNED, "  ", null, NOW))
                .isInstanceOf(InvalidBypassDomainException.class);
    }

    @Test
    void 해제하면_비활성이_되고_나머지_값은_그대로_남는다() {
        UUID admin = UUID.randomUUID();
        BypassDomain domain = BypassDomain.register(BypassHost.of("Update.Vendor.com"), BypassCategory.PINNED,
                "피닝", admin, NOW);

        domain.deactivate();

        assertThat(domain.isActive()).isFalse();
        assertThat(domain.getDomainPattern()).isEqualTo("update.vendor.com");
        assertThat(domain.getCategory()).isEqualTo(BypassCategory.PINNED);
        assertThat(domain.getReason()).isEqualTo("피닝");
        assertThat(domain.getCreatedBy()).isEqualTo(admin);
        assertThat(domain.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void 이미_비활성인_상태에서_해제하면_예외가_난다() {
        BypassDomain domain = newDomain();
        domain.deactivate();

        assertThatThrownBy(domain::deactivate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 해제한_뒤에는_다시_재활성화할_수_있다() {
        BypassDomain domain = newDomain();
        domain.deactivate();

        domain.reactivate(BypassCategory.SECURITY_UPDATE, "새 사유", null);

        assertThat(domain.isActive()).isTrue();
        assertThat(domain.getCategory()).isEqualTo(BypassCategory.SECURITY_UPDATE);
    }

    @Test
    void 활성_상태에서_재활성화하면_예외가_난다() {
        BypassDomain domain = newDomain();

        assertThatThrownBy(() -> domain.reactivate(BypassCategory.SECURITY_UPDATE, "새 사유", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
