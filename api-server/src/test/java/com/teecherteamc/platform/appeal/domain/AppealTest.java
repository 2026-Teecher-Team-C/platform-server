package com.teecherteamc.platform.appeal.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
}
