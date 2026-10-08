package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA가 구현을 자동으로 만들어 주는 DB 접근 도구.
 * 같은 패키지의 Adapter만 쓰도록 public을 붙이지 않았다.
 */
interface BlacklistEntryJpaRepository extends JpaRepository<BlacklistEntry, HashKey> {

    /** 활성(is_active = true)인 행이 있으면 true. 조회 사슬 ②가 쓴다. */
    @Query("select count(e) > 0 from BlacklistEntry e where e.id = :key and e.active = true")
    boolean existsActive(@Param("key") HashKey key);
}