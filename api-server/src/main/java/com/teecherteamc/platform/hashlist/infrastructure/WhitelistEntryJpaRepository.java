package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 화이트리스트용 DB 접근 도구. Spring Data JPA가 구현을 자동으로 만든다.
 * 같은 패키지의 Adapter만 쓰도록 public을 붙이지 않았다.
 */
interface WhitelistEntryJpaRepository extends JpaRepository<WhitelistEntry, HashKey> {

    /**
     * 활성이면서 now 시점에 만료되지 않은 행이 있으면 true. 조회 사슬 ①이 쓴다.
     * WhitelistEntry.isEffectiveAt() 과 같은 규칙이어야 한다.
     */
    @Query("""
            select count(e) > 0 from WhitelistEntry e
            where e.id = :key and e.active = true
              and (e.expiresAt is null or e.expiresAt > :now)
            """)
    boolean existsEffective(@Param("key") HashKey key, @Param("now") Instant now);
}