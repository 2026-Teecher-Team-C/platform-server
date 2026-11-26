package com.teecherteamc.platform.lookup.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.teecherteamc.platform.lookup.application.BloomRebuildRequests;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class RedisBloomFilterPortImplTest {

    @Test
    void 추가가_실패하면_던지지_않고_재구축을_요청한다() {
        RedisConnectionFactory broken = mock(RedisConnectionFactory.class);
        when(broken.getConnection()).thenThrow(new RedisConnectionFailureException("down"));
        var requests = new BloomRebuildRequests();
        var port = new RedisBloomFilterPortImpl(broken, 1000, 0.01, requests);

        assertThatCode(() -> port.add("aa".repeat(32))).doesNotThrowAnyException();
        assertThat(requests.take()).contains("add_failed");

        port.addAll(List.of("bb".repeat(32)));
        assertThat(requests.take()).contains("add_failed");
    }
}
