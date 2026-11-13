package com.teecherteamc.platform.sample.interfaces;

import com.teecherteamc.platform.sample.domain.SampleDetail;

/** 읽기 전용 계약. 다운로드 주소나 이력으로 추정한 메타데이터를 포함하지 않는다. */
public record SampleDetailResponse(String sha256, SampleDetail.Blacklist blacklist, SampleDetail.Fingerprint fingerprint) {
    public static SampleDetailResponse from(SampleDetail sample) {
        return new SampleDetailResponse(sample.sha256(), sample.blacklist(), sample.fingerprint());
    }
}
