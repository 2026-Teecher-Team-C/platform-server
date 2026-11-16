package com.teecherteamc.platform.verdict.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서버와 에이전트(py-tlsh)가 공유하는 기준 벡터. 같은 바이트에서 같은 문자열이 나와야 한다.
 * 이 파일을 바꾸면 에이전트 쪽 복사본도 함께 바꿔야 한다.
 */
class TlshGoldenVectorTest {
    @TempDir Path directory;
    private final OfficialTlshCalculator calculator = new OfficialTlshCalculator();

    static Map<String, byte[]> inputs() {
        byte[] random = new byte[4096];
        new Random(42).nextBytes(random);
        byte[] padded = java.util.Arrays.copyOf(random, random.length + 64);
        java.util.Arrays.fill(padded, random.length, padded.length, (byte) ' ');
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put("short_49_bytes", java.util.Arrays.copyOf(random, 49));
        map.put("fifty_bytes", java.util.Arrays.copyOf(random, 50));
        map.put("random_4k", random);
        map.put("random_4k_plus_64_spaces", padded);
        map.put("zeros_1k", new byte[1024]);
        return map;
    }

    @Test
    void official_calculator_matches_golden_vectors() throws Exception {
        var mapper = JsonMapper.builder().build();
        List<Map<String, String>> golden;
        try (var in = getClass().getResourceAsStream("/tlsh-golden.json")) {
            assertThat(in).as("tlsh-golden.json").isNotNull();
            golden = mapper.readValue(in, new TypeReference<List<Map<String, String>>>() {});
        }
        assertThat(golden).hasSize(inputs().size());
        for (var row : golden) {
            byte[] bytes = HexFormat.of().parseHex(row.get("hex"));
            assertThat(bytes).as(row.get("name")).isEqualTo(inputs().get(row.get("name")));
            assertThat(calculate(row.get("name"), bytes)).as(row.get("name")).isEqualTo(row.get("tlsh"));
        }
        assertThat(golden).filteredOn(r -> r.get("tlsh") == null).extracting(r -> r.get("name"))
                .containsExactlyInAnyOrder("short_49_bytes", "zeros_1k");
        assertThat(golden).filteredOn(r -> r.get("tlsh") != null).allSatisfy(r -> assertThat(r.get("tlsh")).startsWith("T1"));
    }

    @Test
    void null_cases_have_expected_statuses() throws Exception {
        var inputs = inputs();
        assertThat(calculator.calculate(write("a", inputs.get("short_49_bytes"))).status()).isEqualTo("TOO_SHORT");
        assertThat(calculator.calculate(write("b", inputs.get("zeros_1k"))).status()).isEqualTo("LOW_ENTROPY");
    }

    private String calculate(String name, byte[] bytes) {
        try {
            return calculator.calculate(write(name, bytes)).tlsh();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Path write(String name, byte[] bytes) throws java.io.IOException {
        return Files.write(directory.resolve(name), bytes);
    }
}
