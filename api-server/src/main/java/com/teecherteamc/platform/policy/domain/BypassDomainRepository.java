package com.teecherteamc.platform.policy.domain;

import java.util.Optional;

/**
 * 바이패스 도메인 저장소가 할 수 있어야 하는 일의 약속. (쓰기용)
 * 실제 DB 접근 구현은 infrastructure 폴더의 BypassDomainRepositoryAdapter가 한다.
 * 목록 조회는 읽기 전용 포트인 ListBypassDomainsPort가 따로 맡는다.
 */
public interface BypassDomainRepository {

    /**
     * 저장하고 곧바로 DB에 반영한다. 같은 host가 동시에 등록되어 UNIQUE 제약에 걸리면,
     * 트랜잭션이 끝날 때가 아니라 이 호출에서 예외가 난다. (유스케이스 안에서 409로 바꾸기 위해서다)
     */
    BypassDomain save(BypassDomain domain);

    /**
     * 비활성 항목까지 포함해서 찾는다.
     * domain_pattern의 UNIQUE 제약이 is_active를 보지 않으므로, 등록 전에 이미 있는 행인지 확인하는 데 쓴다.
     */
    Optional<BypassDomain> findByHost(BypassHost host);
}
