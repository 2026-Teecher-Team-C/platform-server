package com.teecherteamc.platform.policy.infrastructure;

import com.teecherteamc.platform.policy.domain.BypassDomain;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA가 구현을 자동으로 만들어 주는 DB 접근 도구.
 * 같은 패키지의 Adapter만 쓰도록 public을 붙이지 않았다.
 */
interface BypassDomainJpaRepository extends JpaRepository<BypassDomain, UUID> {

    /** 메서드 이름에서 쿼리가 만들어진다: WHERE domain_pattern = ? */
    Optional<BypassDomain> findByDomainPattern(String domainPattern);
}
