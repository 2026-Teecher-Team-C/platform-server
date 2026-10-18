package com.teecherteamc.platform.lookup.infrastructure;

import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * file_verdicts에 한 번이라도 기록된 모든 sha256을 담는 Redis 비트맵. SETBIT/GETBIT을
 * RedisConnection에서 직접 쓴다(RedisTemplate의 값 직렬화는 필요 없다 — 비트 연산은 키/오프셋만
 * 다룬다).
 *
 * <p>m(비트 수)/k(해시 함수 개수)는 application.yml의 기대 해시 개수(n)·목표 오탐률(p)로부터 표준
 * 공식으로 계산한다: m = ceil(-n·ln(p)/ln(2)^2), k = round((m/n)·ln(2)). 해시 위치는 SHA-256
 * 다이제스트를 앞/뒤 8바이트로 나눠 double hashing(h_i = h1 + i·h2 mod m)으로 k개 산출한다 — 새
 * 의존성(Guava/MurmurHash) 없이 TokenHash와 같은 MessageDigest만 쓴다.
 */
@Component
class RedisBloomFilterPortImpl implements BloomFilterPort {

    private static final byte[] KEY = "bloom:file_verdicts".getBytes(StandardCharsets.UTF_8);
    private static final String REBUILDING_KEY_PREFIX = "bloom:file_verdicts:rebuilding:";

    private final RedisConnectionFactory connectionFactory;
    private final long bitSize;
    private final int hashCount;

    RedisBloomFilterPortImpl(
            RedisConnectionFactory connectionFactory,
            @Value("${lookup.bloom-filter.expected-hash-count}") long expectedHashCount,
            @Value("${lookup.bloom-filter.false-positive-rate}") double falsePositiveRate) {
        this.connectionFactory = connectionFactory;
        this.bitSize = Math.max(
                1, (long) Math.ceil(-(expectedHashCount * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2))));
        this.hashCount = Math.max(1, (int) Math.round((double) bitSize / expectedHashCount * Math.log(2)));
    }

    @Override
    public boolean mightContain(String sha256) {
        long[] positions = positions(sha256);
        return execute(connection -> {
            // k번 GETBIT을 한 번씩 왕복하는 대신 파이프라인으로 묶어서 한 번만 왕복한다 — 다운로드마다
            // 호출되는 가장 뜨거운 읽기 경로라 지연에 민감하다.
            connection.openPipeline();
            for (long position : positions) {
                connection.getBit(KEY, position);
            }
            List<Object> results = connection.closePipeline();
            for (Object result : results) {
                if (!(result instanceof Boolean bit) || !bit) {
                    return false;
                }
            }
            return true;
        });
    }

    @Override
    public void rebuild(List<String> cachedHashes) {
        // 호출마다 고유한 임시 키를 쓴다 — 고정된 키를 공유하면 기동 시 자동 1차 빌드와 수동/테스트
        // 재구축이 동시에 겹칠 때 서로의 del/setBit을 밟을 수 있다(레이스).
        byte[] rebuildingKey = (REBUILDING_KEY_PREFIX + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        execute(connection -> {
            // 빈 목록이어도 RENAME이 성공하려면 키가 존재해야 한다.
            connection.setBit(rebuildingKey, 0, false);
            connection.openPipeline();
            for (String sha256 : cachedHashes) {
                for (long position : positions(sha256)) {
                    connection.setBit(rebuildingKey, position, true);
                }
            }
            connection.closePipeline();
            // RENAME은 원자적이다 — 재구축 중간 상태를 읽는 레이스가 없다.
            connection.rename(rebuildingKey, KEY);
            return null;
        });
    }

    private <T> T execute(Function<RedisConnection, T> action) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return action.apply(connection);
        }
    }

    private long[] positions(String sha256) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(sha256.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
        long h1 = toLong(digest, 0);
        long h2 = toLong(digest, 8);
        long[] result = new long[hashCount];
        for (int i = 0; i < hashCount; i++) {
            result[i] = Math.floorMod(h1 + (long) i * h2, bitSize);
        }
        return result;
    }

    private static long toLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }
}
