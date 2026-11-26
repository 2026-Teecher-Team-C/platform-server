package com.teecherteamc.platform.lookup.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BloomRebuildRequestsTest {

    @Test
    void 요청이_없으면_비어있다() {
        assertThat(new BloomRebuildRequests().take()).isEmpty();
    }

    @Test
    void 여러_번_요청해도_한_번_꺼내면_빈다() {
        var requests = new BloomRebuildRequests();
        requests.request("missing");
        requests.request("add_failed");

        assertThat(requests.take()).contains("missing");
        assertThat(requests.take()).isEmpty();
    }
}
