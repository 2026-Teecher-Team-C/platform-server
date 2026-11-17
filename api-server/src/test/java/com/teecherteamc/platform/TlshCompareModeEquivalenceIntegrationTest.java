package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore.Assessment;
import com.teecherteamc.platform.verdict.infrastructure.JdbcSimilarityDecisionStore;
import com.teecherteamc.platform.verdict.infrastructure.ParsedSimilarityCorpus;
import com.trendmicro.tlsh.TlshCreator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고도화 테스트 시나리오 C(동일성 검사). L-value 창으로 거른 indexed 비교가 전체 비교(bruteforce)와 같은 결론을 낸다:
 * 가장 가까운 후보가 ≤70(WARN 이하)이면 기준 샘플·거리가 같고, 아니면 둘 다 >70(또는 후보 없음)이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TlshCompareModeEquivalenceIntegrationTest {

    private static final int WARN_LIMIT = 70;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void indexed와_bruteforce의_결론이_같다() {
        Random random = new Random(20261118);
        List<byte[]> samples = new ArrayList<>();
        List<String> corpus = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            byte[] data = new byte[200 + random.nextInt(60_000)];
            random.nextBytes(data);
            samples.add(data);
            corpus.add(tlsh(data));
        }
        String salt = "equiv-" + System.nanoTime();
        jdbc.batchUpdate("INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, ?)",
                corpus.stream().map(t -> new Object[] {sha(salt, t), salt}).toList());
        jdbc.batchUpdate("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) "
                + "VALUES (?, ?, 'CALCULATED', 'equiv', 'VERIFIED_UPLOAD')",
                corpus.stream().map(t -> new Object[] {sha(salt, t), t}).toList());

        JdbcSimilarityDecisionStore bruteforce = store("bruteforce");
        JdbcSimilarityDecisionStore indexed = store("indexed");
        int nearMatches = 0;
        for (int i = 0; i < 300; i++) {
            String input = i % 2 == 0 ? tlsh(mutate(samples.get(random.nextInt(samples.size())), random)) : tlsh(randomBytes(random));
            Assessment all = bruteforce.compareTlsh(input);
            Assessment window = indexed.compareTlsh(input);
            if (all.distance() != null && all.distance() <= WARN_LIMIT) {
                nearMatches++;
                assertThat(window.sample()).as(input).isEqualTo(all.sample());
                assertThat(window.distance()).as(input).isEqualTo(all.distance());
            } else {
                assertThat(window.distance() == null || window.distance() > WARN_LIMIT).as(input).isTrue();
            }
        }
        assertThat(nearMatches).as("≤70 경우를 충분히 검사했다").isGreaterThan(50);
    }

    private JdbcSimilarityDecisionStore store(String mode) {
        return new JdbcSimilarityDecisionStore(jdbc, new ParsedSimilarityCorpus(new SimpleMeterRegistry()), 0, mode);
    }

    private static byte[] mutate(byte[] original, Random random) {
        byte[] copy = original.clone();
        int changes = 1 + random.nextInt(Math.max(1, copy.length / 40));
        for (int i = 0; i < changes; i++) {
            copy[random.nextInt(copy.length)] = (byte) random.nextInt(256);
        }
        return copy;
    }

    private static byte[] randomBytes(Random random) {
        byte[] data = new byte[200 + random.nextInt(60_000)];
        random.nextBytes(data);
        return data;
    }

    private static String tlsh(byte[] data) {
        TlshCreator creator = new TlshCreator();
        creator.update(data);
        return creator.getHash(true).getEncoded();
    }

    private static String sha(String salt, String tlsh) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((salt + tlsh).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
