package com.teecherteamc.platform.admin.domain;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 관리자 refresh 토큰 저장소. 구현은 infrastructure의 RedisRefreshTokenStore가 한다.
 * 토큰 원문은 저장하지 않는다(해시로만 찾는다).
 */
public interface RefreshTokenStore {

    void save(String refreshToken, UUID adminId, Duration ttl);

    /** 토큰을 꺼내면서 지운다. 같은 토큰은 한 번만 쓸 수 있다. 없거나 만료됐으면 빈 값이다. */
    Optional<UUID> consume(String refreshToken);

    /** 그 관리자의 토큰일 때만 지운다. 남의 토큰이나 모르는 토큰이면 아무것도 하지 않는다. */
    void revoke(String refreshToken, UUID adminId);
}
