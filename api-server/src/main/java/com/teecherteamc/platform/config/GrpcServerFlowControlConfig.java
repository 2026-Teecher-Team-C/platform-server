package com.teecherteamc.platform.config;

import io.grpc.netty.NettyServerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.ServerBuilderCustomizer;

/**
 * gRPC 서버가 스트림마다 미리 받아 두는 양(HTTP/2 수신 흐름 제어 창)을 줄인다.
 * 기본 1MiB면 업로드 300건이 동시에 들어올 때 받아 둔 청크가 Netty 다이렉트 버퍼에 최대 약 490MiB까지
 * 쌓인다(부하 시험 측정). 256KiB로 줄이면 약 130MiB로 내려가고, 성공률은 같다(300/300).
 * Spring gRPC에 이 값의 설정 키가 없어 빌더를 직접 고친다.
 */
@Configuration
class GrpcServerFlowControlConfig {

    @Bean
    ServerBuilderCustomizer<NettyServerBuilder> flowControlWindowCustomizer(
            @Value("${grpc-server.flow-control-window}") int window) {
        return builder -> builder.flowControlWindow(window);
    }
}
