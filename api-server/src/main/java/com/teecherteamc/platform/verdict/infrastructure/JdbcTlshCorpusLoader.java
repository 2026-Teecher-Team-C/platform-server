package com.teecherteamc.platform.verdict.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 메모리 corpus 원본. 토큰과 행을 같은 MVCC 스냅샷(REPEATABLE READ 읽기 전용)에서 읽어 둘이 어긋나지 않게 한다.
 * advisory 공유 락은 잡지 않는다 — 수십 초 걸리는 전체 읽기가 목록 쓰기(배타 락)를 막으면 안 된다.
 */
@Component
public class JdbcTlshCorpusLoader {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcTlshCorpusLoader(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
    }

    public TlshCorpusSnapshot loadCommitted() {
        return transaction.execute(status -> load(jdbc));
    }

    /** 호출자의 트랜잭션에서 읽는다. 토큰을 먼저 읽어 같은 스냅샷의 행과 짝짓는다. */
    public static TlshCorpusSnapshot load(JdbcTemplate jdbc) {
        String token = jdbc.queryForObject("SELECT token FROM current_similarity_corpus_token", String.class);
        TlshCorpusSnapshot.Builder builder = TlshCorpusSnapshot.builder(token);
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT sha256, tlsh FROM active_malicious_fingerprints");
            statement.setFetchSize(10_000);
            return statement;
        }, (RowCallbackHandler) rs -> builder.add(rs.getString(1), rs.getString(2)));
        return builder.build();
    }
}
