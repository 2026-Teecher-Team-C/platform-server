package com.teecherteamc.platform.verdict.domain;

import java.util.Optional;

/**
 * 판정을 저장하는 트랜잭션 안에서 지금 활성 룰셋 키를 읽는 포트. 읽는 동시에 활성 룰셋에 공유 잠금을 걸어,
 * 저장이 커밋될 때까지 활성화·활성 버전 룰 토글이 끼어들지 못하게 한다. 룰셋 애그리거트는 바꾸지 않는다.
 */
public interface ActiveRulesetKeyPort {

    /** 호출자 트랜잭션이 있어야 한다. 활성 행을 찾지 못하면(활성화가 막 커밋됨) 비어 있다. */
    Optional<String> lockActiveKey();
}
