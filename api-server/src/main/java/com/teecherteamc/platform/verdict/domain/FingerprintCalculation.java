package com.teecherteamc.platform.verdict.domain;

/** 산출 불가도 값으로 보존한다. TLSH가 없다는 이유로 SHA/YARA 판정을 바꾸지 않는다. */
public record FingerprintCalculation(String tlsh, String status) {
    public static final String ALGORITHM_VERSION =
            "TLSH-128-1-T1-force50-java-ebdec8fde93a4ac359437f4f3796c78d3ae433bf";
}
