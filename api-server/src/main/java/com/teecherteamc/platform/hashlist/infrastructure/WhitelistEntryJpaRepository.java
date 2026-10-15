package com.teecherteamc.platform.hashlist.infrastructure;

import com.teecherteamc.platform.hashlist.domain.HashKey;
import com.teecherteamc.platform.hashlist.domain.WhitelistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 화이트리스트용 DB 접근 도구. Spring Data JPA가 구현을 자동으로 만든다.
 * 같은 패키지의 Adapter만 쓰도록 public을 붙이지 않았다.
 */
interface WhitelistEntryJpaRepository extends JpaRepository<WhitelistEntry, HashKey> {
}