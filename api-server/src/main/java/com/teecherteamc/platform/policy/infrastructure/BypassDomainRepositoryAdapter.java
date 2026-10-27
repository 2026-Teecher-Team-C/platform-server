package com.teecherteamc.platform.policy.infrastructure;

import com.teecherteamc.platform.policy.domain.BypassDomain;
import com.teecherteamc.platform.policy.domain.BypassDomainRepository;
import com.teecherteamc.platform.policy.domain.BypassHost;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * domain의 BypassDomainRepository(약속)를 JPA로 구현한다.
 * 실제 DB 작업은 BypassDomainJpaRepository에 맡기고, 여기서는 연결만 한다.
 */
@Repository
class BypassDomainRepositoryAdapter implements BypassDomainRepository {

    private final BypassDomainJpaRepository jpaRepository;

    BypassDomainRepositoryAdapter(BypassDomainJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public BypassDomain save(BypassDomain domain) {
        return jpaRepository.saveAndFlush(domain);
    }

    @Override
    public Optional<BypassDomain> findByHost(BypassHost host) {
        return jpaRepository.findByDomainPattern(host.value());
    }
}
