package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.BlacklistEntry;
import com.teecherteamc.platform.hashlist.domain.HashKey;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA가 구현을 자동으로 만들어 주는 DB 접근 도구.
 * 같은 패키지의 Adapter만 쓰도록 public을 붙이지 않았다.
 */
interface BlacklistEntryJpaRepository extends JpaRepository<BlacklistEntry, HashKey> {
}