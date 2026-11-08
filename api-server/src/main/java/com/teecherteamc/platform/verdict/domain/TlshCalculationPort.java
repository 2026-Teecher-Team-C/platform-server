package com.teecherteamc.platform.verdict.domain;

import java.nio.file.Path;

public interface TlshCalculationPort {
    FingerprintCalculation calculate(Path file);
}
