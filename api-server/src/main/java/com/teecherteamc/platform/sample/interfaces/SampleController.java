package com.teecherteamc.platform.sample.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.sample.domain.FindSamplePort;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SampleController {
    private final FindSamplePort samples;
    public SampleController(FindSamplePort samples) { this.samples = samples; }

    // 최신 SecurityConfig의 기본 규칙에 따라 ADMIN만 조회한다. USER/ANALYST 권한을 암묵적으로 넓히지 않는다.
    @GetMapping("/api/admin/samples/{sha256}")
    public SampleDetailResponse find(@PathVariable String sha256) {
        // 원문 형식을 먼저 검증한다. 공백 제거 등으로 잘못된 식별자를 고쳐서 조회하지 않는다.
        if (!sha256.matches("[0-9a-fA-F]{64}")) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "sha256 must be exactly 64 hexadecimal characters");
        }
        return samples.find(sha256.toLowerCase(Locale.ROOT)).map(SampleDetailResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Current sample metadata not found"));
    }
}
