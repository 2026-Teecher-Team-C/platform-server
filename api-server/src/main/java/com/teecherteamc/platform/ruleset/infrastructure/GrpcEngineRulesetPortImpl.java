package com.teecherteamc.platform.ruleset.infrastructure;

import com.teecherteamc.platform.proto.engine.v1.CompileRulesRequest;
import com.teecherteamc.platform.proto.engine.v1.CompileRulesResponse;
import com.teecherteamc.platform.proto.engine.v1.DetectionEngineGrpc;
import com.teecherteamc.platform.proto.engine.v1.LoadRulesetRequest;
import com.teecherteamc.platform.ruleset.domain.CompileResult;
import com.teecherteamc.platform.ruleset.domain.CompiledRule;
import com.teecherteamc.platform.ruleset.domain.EngineRulesetPort;
import com.teecherteamc.platform.ruleset.domain.EngineRulesetUnavailableException;
import com.teecherteamc.platform.ruleset.domain.RulesetSnapshot;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 탐지 엔진의 CompileRules/LoadRuleset(단발 unary)를 블로킹 스텁으로 호출한다. */
@Component
class GrpcEngineRulesetPortImpl implements EngineRulesetPort {

    private static final long TIMEOUT_SECONDS = 10;

    private final DetectionEngineGrpc.DetectionEngineBlockingStub stub;

    GrpcEngineRulesetPortImpl(DetectionEngineGrpc.DetectionEngineBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public CompileResult compile(String source) {
        try {
            CompileRulesResponse response = stub.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .compileRules(CompileRulesRequest.newBuilder().setSource(source).build());
            return new CompileResult(
                    response.getOk(),
                    response.getErrorMessage(),
                    response.getRulesList().stream()
                            .map(r -> new CompiledRule(r.getName(), r.getSeverity()))
                            .toList());
        } catch (StatusRuntimeException e) {
            throw new EngineRulesetUnavailableException("엔진 룰 컴파일 호출 실패", e);
        }
    }

    @Override
    public void load(RulesetSnapshot snapshot) {
        try {
            stub.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .loadRuleset(LoadRulesetRequest.newBuilder()
                            .setRulesetsVersion(snapshot.version())
                            .setRulesetKey(snapshot.key())
                            .setSource(snapshot.source())
                            .addAllDisabledRuleNames(snapshot.disabledRuleNames())
                            .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                throw new IllegalStateException("엔진이 룰셋 적재를 거부했다: " + e.getStatus().getDescription(), e);
            }
            throw new EngineRulesetUnavailableException("엔진 룰셋 적재 호출 실패", e);
        }
    }
}
