package com.teecherteamc.platform.ruleset.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.FakeDetectionEngineService;
import com.teecherteamc.platform.TestcontainersConfiguration;
import com.teecherteamc.platform.ruleset.domain.CompileResult;
import com.teecherteamc.platform.ruleset.domain.CompiledRule;
import com.teecherteamc.platform.ruleset.domain.EngineRulesetPort;
import com.teecherteamc.platform.ruleset.domain.EngineRulesetUnavailableException;
import com.teecherteamc.platform.ruleset.domain.RulesetSnapshot;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import(TestcontainersConfiguration.class)
class GrpcEngineRulesetPortImplTest {

    @Autowired
    private EngineRulesetPort port;

    @BeforeEach
    void resetFake() {
        FakeDetectionEngineService.reset();
    }

    @Test
    void 컴파일_결과를_도메인_값으로_바꾼다() {
        String source = "rule a_rule { meta: severity = \"HIGH\" condition: true }\nrule b_rule { condition: false }";

        CompileResult result = port.compile(source);

        assertThat(result.ok()).isTrue();
        assertThat(result.rules()).containsExactly(
                new CompiledRule("a_rule", "HIGH"), new CompiledRule("b_rule", "MEDIUM"));
    }

    @Test
    void 문법_오류는_ok_false로_돌려준다() {
        CompileResult result = port.compile("SYNTAX_ERROR");

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("line 1: syntax error");
    }

    @Test
    void 적재하면_가짜_엔진의_키가_바뀐다() {
        RulesetSnapshot snapshot = new RulesetSnapshot(5, "rule x { condition: true }", List.of("x"));

        port.load(snapshot);

        assertThat(FakeDetectionEngineService.loadedKey()).isEqualTo(snapshot.key());
        assertThat(FakeDetectionEngineService.loadCount()).isEqualTo(1);
        assertThat(FakeDetectionEngineService.loadedVersion()).isEqualTo(5);
    }

    @Test
    void 엔진이_불가하면_EngineRulesetUnavailableException을_던진다() {
        FakeDetectionEngineService.failNextCall();
        assertThatThrownBy(() -> port.compile("rule x {}"))
                .isInstanceOf(EngineRulesetUnavailableException.class);

        FakeDetectionEngineService.failNextCall();
        assertThatThrownBy(() -> port.load(new RulesetSnapshot(1, "s", List.of())))
                .isInstanceOf(EngineRulesetUnavailableException.class);
    }
}
