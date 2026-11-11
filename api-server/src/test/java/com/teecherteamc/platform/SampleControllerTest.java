package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.sample.domain.FindSamplePort;
import com.teecherteamc.platform.sample.interfaces.SampleController;
import org.junit.jupiter.api.Test;

class SampleControllerTest {
    @Test
    void malformedLiteralNeverReachesLookup() {
        var port = mock(FindSamplePort.class);
        var controller = new SampleController(port);
        for (String sha : java.util.List.of("ab", " " + "a".repeat(64), "a".repeat(64) + " ", "g".repeat(64))) {
            assertThatThrownBy(() -> controller.find(sha)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(port);
    }
}
