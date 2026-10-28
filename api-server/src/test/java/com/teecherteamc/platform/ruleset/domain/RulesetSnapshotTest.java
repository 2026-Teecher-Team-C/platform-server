package com.teecherteamc.platform.ruleset.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RulesetSnapshotTest {

    @Test
    void 키는_꺼진_룰_순서와_무관하다() {
        String ab = new RulesetSnapshot(3, "src", List.of("a", "b")).key();
        String ba = new RulesetSnapshot(3, "src", List.of("b", "a")).key();
        String none = new RulesetSnapshot(3, "src", List.of()).key();

        assertThat(ab).isEqualTo(ba).matches("^3:[0-9a-f]{16}$");
        assertThat(none).isNotEqualTo(ab).matches("^3:[0-9a-f]{16}$");
    }
}
