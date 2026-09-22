package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.proto.engine.v1.DetectionEngineGrpc;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import org.junit.jupiter.api.Test;

class ProtoContractTest {

    @Test
    void 레포_루트_proto에서_gRPC_서비스_스텁이_생성된다() {
        assertThat(VerdictServiceGrpc.getServiceDescriptor().getName())
                .isEqualTo("teecher.verdict.v1.VerdictService");
        assertThat(DetectionEngineGrpc.getServiceDescriptor().getName())
                .isEqualTo("teecher.engine.v1.DetectionEngine");
    }
}
