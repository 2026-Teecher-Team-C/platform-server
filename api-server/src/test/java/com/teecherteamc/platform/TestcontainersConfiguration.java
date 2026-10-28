package com.teecherteamc.platform;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }

    // 전용 Testcontainers Redis 모듈 없이 범용 GenericContainer로 띈다 — 이미지 이름으로 Spring
    // Boot가 자동으로 spring.data.redis.host/port를 연결해준다(postgres 빈과 같은 패턴).
    @Bean
    @ServiceConnection("redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);
    }

    // 관리자 access 토큰(JWT) 서명 키. 운영은 JWT_SECRET 환경 변수로 넣고 기본값이 없어서, 통합 테스트는 여기서 채운다.
    @Bean
    DynamicPropertyRegistrar jwtSecretProperty() {
        return registry -> registry.add("platform.auth.jwt.secret", () -> "integration-test-jwt-secret-at-least-32-bytes");
    }
}
