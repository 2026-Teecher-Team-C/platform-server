package com.teecherteamc.platform.admin.infrastructure;

import com.teecherteamc.platform.admin.domain.RefreshTokenStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * refresh 토큰을 Redis에 둔다. 키는 토큰의 SHA-256이라 Redis가 새어도 토큰 원문은 나오지 않는다.
 * 값은 admin_id, TTL은 refresh 토큰 유효 기간이다.
 */
@Repository
class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String KEY_PREFIX = "admin:refresh:";

    private final StringRedisTemplate redis;

    RedisRefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(String refreshToken, UUID adminId, Duration ttl) {
        redis.opsForValue().set(keyOf(refreshToken), adminId.toString(), ttl);
    }

    @Override
    public Optional<UUID> consume(String refreshToken) {
        // GETDEL: 같은 토큰으로 동시에 두 번 refresh해도 한쪽만 성공한다.
        return Optional.ofNullable(redis.opsForValue().getAndDelete(keyOf(refreshToken))).map(UUID::fromString);
    }

    @Override
    public void revoke(String refreshToken, UUID adminId) {
        String key = keyOf(refreshToken);
        if (adminId.toString().equals(redis.opsForValue().get(key))) {
            redis.delete(key);
        }
    }

    private static String keyOf(String refreshToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }
}
