package com.teecherteamc.platform.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.grpc.netty.NettyServerBuilder;
import org.junit.jupiter.api.Test;

class GrpcServerFlowControlConfigTest {

    @Test
    void gRPC_서버의_스트림당_수신_창을_설정값으로_맞춘다() {
        NettyServerBuilder builder = mock(NettyServerBuilder.class);

        new GrpcServerFlowControlConfig().flowControlWindowCustomizer(256 * 1024).customize(builder);

        verify(builder).flowControlWindow(256 * 1024);
    }
}
