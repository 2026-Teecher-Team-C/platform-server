package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.SimilarityDecisionStore.Thresholds;
import com.teecherteamc.platform.verdict.domain.SimilarityThresholdPort;
import com.teecherteamc.platform.verdict.domain.SimilarityThresholds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유사도 임계값의 DB 구현. 값은 similarity_corpus_state 한 행에 있다.
 * 호출자가 연 트랜잭션 안에서만 쓴다. 락이 커밋까지 유지돼야 하기 때문이다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSimilarityThresholdPort implements SimilarityThresholdPort {
    private final JdbcTemplate jdbc;

    public JdbcSimilarityThresholdPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Thresholds current() {
        return read();
    }

    public Thresholds replace(SimilarityThresholds next) {
        // 블랙리스트 일괄 적재와 같은 corpus 배타 락이다. 진행 중인 검사(공유 락)가 끝나길 기다린 뒤 값을 바꾸고,
        // 그 사이에 시작하려는 검사는 이 변경이 끝날 때까지 들어오지 못한다. 키 번호는 DB 함수에서만 얻는다.
        // 적재처럼 오래 걸리는 작업과 겹쳐도 요청이 무한정 매달리지 않도록 대기 시간을 제한한다.
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        jdbc.execute("SELECT pg_advisory_xact_lock(similarity_lock_key('corpus')::bigint)");

        // 락을 잡은 뒤에 읽어야 감사 로그에 남길 이전 값이 정확하다.
        Thresholds previous = read();

        // 토큰 뷰가 이 두 값에서 정책 문자열을 만들므로, 여기서 바뀌면 토큰도 바뀌어
        // 캐시된 CLEAN/SIMILARITY 판정이 다음 조회부터 stale로 처리된다. revision은 올리지 않아도 된다.
        int updated = jdbc.update("UPDATE similarity_corpus_state SET block_distance=?, warn_distance=?",
                next.blockDistance(), next.warnDistance());
        if (updated != 1) {
            throw new IllegalStateException("similarity_corpus_state must have exactly one row");
        }
        return previous;
    }

    private Thresholds read() {
        return jdbc.queryForObject(
                "SELECT block_distance, warn_distance FROM similarity_corpus_state",
                (rs, rowNum) -> new Thresholds(rs.getInt(1), rs.getInt(2)));
    }
}