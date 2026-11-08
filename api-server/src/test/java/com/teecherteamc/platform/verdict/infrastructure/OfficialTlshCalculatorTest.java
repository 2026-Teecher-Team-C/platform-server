package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialTlshCalculatorTest {
    @TempDir Path directory;
    private final OfficialTlshCalculator calculator = new OfficialTlshCalculator();

    @Test
    void fifty_bytes_match_official_cpp_reference_and_legacy_encoding_roundtrips() throws Exception {
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/tlsh/reference.txt")) {
            fixture = input.readAllBytes();
        }
        Path file = Files.write(directory.resolve("fifty"), Arrays.copyOf(fixture, 50));
        var result = calculator.calculate(file);
        assertThat(result.status()).isEqualTo("CALCULATED");
        assertThat(result.tlsh()).isEqualTo("T11890043150034750FF04F71557447000DD1033F110570D43540DD1DC50DC143DC47075");
        var legacy = com.trendmicro.tlsh.Tlsh.fromTlshStr(result.tlsh().substring(2));
        assertThat(legacy.totalDiff(com.trendmicro.tlsh.Tlsh.fromTlshStr(result.tlsh()), true)).isZero();
    }

    @Test
    void unreadable_file_is_unavailable_not_low_entropy() {
        var result = calculator.calculate(directory.resolve("missing"));
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.tlsh()).isNull();
    }

    @Test
    void streamed_multi_chunk_input_matches_cpp_reference() throws Exception {
        Path file = directory.resolve("large");
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/tlsh/reference.txt")) {
            fixture = input.readAllBytes();
        }
        // 8 KiB 경계를 넘겨도 update의 상태가 보존되는지 공식 계산 결과와 비교한다.
        try (var output = Files.newOutputStream(file)) {
            for (int i = 0; i < 5; i++) {
                output.write(fixture);
            }
        }
        assertThat(calculator.calculate(file).tlsh()).isEqualTo("T1CBE24D74F9407AE28FAE92B7CCFE6BA1509763248ED678CC9474B7C71593294FD00851");
    }
}
