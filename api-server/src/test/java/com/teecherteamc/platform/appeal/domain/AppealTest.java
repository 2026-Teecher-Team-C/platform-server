package com.teecherteamc.platform.appeal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppealTest {

    private static Appeal submit(String threat) {
        return Appeal.submit(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), "사유", "SIMILARITY", threat,
                Instant.now());
    }

    @Test
    void 위협_이름은_255_코드포인트에서_자른다() {
        String threat = "😀".repeat(300);

        String stored = submit(threat).getThreat();

        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(255);
        assertThat(stored).isEqualTo("😀".repeat(255));
    }

    @Test
    void 위협_이름이_255자_이하면_그대로_둔다() {
        assertThat(submit("x".repeat(255)).getThreat()).isEqualTo("x".repeat(255));
    }

    @Test
    void 빈_위협_이름은_null로_저장한다() {
        assertThat(submit("").getThreat()).isNull();
        assertThat(submit(null).getThreat()).isNull();
    }

    @Test
    void 근거_종류를_그대로_저장한다() {
        assertThat(submit("AgentTesla").getEvidenceKind()).isEqualTo("SIMILARITY");
    }

    @Test
    void 승인하면_결정자와_의견과_시각이_남는다() {
        Appeal appeal = submit(null);
        UUID admin = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-26T00:00:00Z");

        appeal.approve(admin, "확인", now);

        assertThat(appeal.getStatus()).isEqualTo(AppealStatus.APPROVED);
        assertThat(appeal.getDecidedBy()).isEqualTo(admin);
        assertThat(appeal.getDecisionNote()).isEqualTo("확인");
        assertThat(appeal.getDecidedAt()).isEqualTo(now);
    }

    @Test
    void 거절에는_의견이_필수다() {
        assertThatThrownBy(() -> submit(null).reject(UUID.randomUUID(), " ", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 이미_결정된_소명은_다시_결정할_수_없다() {
        Appeal appeal = submit(null);
        appeal.reject(UUID.randomUUID(), "악성", Instant.now());

        assertThatThrownBy(() -> appeal.approve(UUID.randomUUID(), null, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> appeal.reject(UUID.randomUUID(), "또", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }
}
