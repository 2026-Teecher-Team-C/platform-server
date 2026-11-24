package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.FingerprintProvenance;
import com.teecherteamc.platform.verdict.domain.SimilarFingerprintSearchPort;
import com.trendmicro.tlsh.Tlsh;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전체 지문을 커서로 한 번 훑으며 Java에서 거리를 계산한다(설계 G1-A). 캐시도 잠금도 쓰지 않는다(G2-A, G7-A).
 * 판정 경로의 JdbcSimilarityDecisionStore와 달리 corpus 잠금을 잡지 않아, 긴 스캔이 블랙리스트 쓰기나
 * CheckHash를 막지 않는다. 단일 SELECT라 문장 시작 시점의 스냅샷을 본다.
 */
// @Repository가 아니라 @Component다. @Repository는 IllegalArgumentException을 데이터 접근 예외로 바꿔서
// 형식 오류를 호출자가 IllegalArgumentException으로 받지 못한다(테스트로 확인). 판정 쪽 JdbcSimilarityDecisionStore도 @Component다.
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSimilarFingerprintSearch implements SimilarFingerprintSearchPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcSimilarFingerprintSearch.class);
    /** 전송 묶음 크기일 뿐 후보 수 상한이 아니다. 끝까지 읽는다. */
    private static final int FETCH_SIZE = 256;

    private final JdbcTemplate jdbc;

    public JdbcSimilarFingerprintSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> calculatedTlsh(String sha256) {
        return jdbc.queryForList("""
                SELECT tlsh FROM file_fingerprints WHERE sha256=? AND calculation_status='CALCULATED'
                """, String.class, sha256).stream().findFirst();
    }

    @Override
    public long scan(String referenceTlsh, Set<FingerprintProvenance> provenances, int maxDistance,
            BiConsumer<String, Integer> sink) {
        if (provenances.isEmpty()) {
            throw new IllegalArgumentException("provenances는 비어 있을 수 없습니다.");
        }
        // 커서를 열기 전에 해석해서, 형식 오류가 DB 읽기 도중이 아니라 바로 드러나게 한다.
        Tlsh reference = Tlsh.fromTlshStr(referenceTlsh);
        String[] names = provenances.stream().map(Enum::name).sorted().toArray(String[]::new);
        long[] counts = {0, 0}; // [거리를 계산한 행, 건너뛴 행]
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT sha256, tlsh FROM file_fingerprints
                    WHERE calculation_status = 'CALCULATED' AND provenance = ANY(?)
                    """);
            statement.setArray(1, connection.createArrayOf("varchar", names));
            statement.setFetchSize(FETCH_SIZE);
            return statement;
        }, (RowCallbackHandler) rs -> {
            int distance;
            try {
                distance = reference.totalDiff(Tlsh.fromTlshStr(rs.getString(2)), true);
            } catch (IllegalArgumentException e) {
                counts[1]++;
                return;
            }
            counts[0]++;
            if (distance <= maxDistance) {
                sink.accept(rs.getString(1), distance);
            }
        });
        if (counts[1] > 0) {
            log.warn("비교할 수 없어 건너뛴 지문 {}건 (비교한 행 {}건)", counts[1], counts[0]);
        }
        return counts[0];
    }
}
