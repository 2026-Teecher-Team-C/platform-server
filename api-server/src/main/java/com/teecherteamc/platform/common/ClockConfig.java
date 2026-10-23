package com.teecherteamc.platform.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 시각을 주입받을 수 있게 Clock을 빈으로 등록한다.
 * 유스케이스가 Instant.now()를 직접 부르지 않고 Clock을 쓰면,
 * 테스트에서 시각을 고정해서 만료 경계 같은 것을 확인할 수 있다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}