package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.HashListWriteLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL 트랜잭션 단위 advisory lock으로 구현한다. 행이 아직 없을 때(처음 등록)도 잠글 수 있어서
 * SELECT ... FOR UPDATE 대신 이걸 쓴다. 트랜잭션이 커밋·롤백되면 자동으로 풀린다.
 */
@Component
class PostgresHashListWriteLock implements HashListWriteLock {

    private final JdbcTemplate jdbcTemplate;

    PostgresHashListWriteLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 트랜잭션 밖에서 부르면 잠금이 바로 풀려 의미가 없으므로 MANDATORY로 막는다.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(HashKey key) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null,
                "hashlist:" + key.getHashType().name() + ":" + key.getHashValue());
    }
}
