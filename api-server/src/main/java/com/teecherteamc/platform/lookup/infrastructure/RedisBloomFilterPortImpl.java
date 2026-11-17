package com.teecherteamc.platform.lookup.infrastructure;

import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RedisBloomFilterPortImpl.class);
    private static final byte[] KEY = "bloom:file_verdicts".getBytes(StandardCharsets.UTF_8);
    private static final String REBUILDING_KEY_PREFIX = "bloom:file_verdicts:rebuilding:";
    /**
     * 새 비트맵(KEYS[1])을 live(KEYS[2])와 OR한 뒤 RENAME한다. Lua라 사이에 다른 명령이 끼지 않아, 스냅샷 뒤
     * add/addAll로 live에 들어간 비트가 교체로 사라지지 않는다. SETBIT은 켠 비트까지만 문자열을 늘리므로 live는 새
     * 비트맵보다 짧을 수 있다(Redis 초기화 뒤 즉시 추가만 있었을 때). live가 더 길면 예전에 비트 수가 더 큰 설정이었다는
     * 뜻이라 OR 없이 교체만 한다. 더 작은 설정의 비트가 섞이는 경우는 오탐(DB 조회)만 늘고 누락은 없다.
     * 대가: 재구축이 옛 비트를 지우지 않는다. 목록·판정은 하드 삭제 경로가 없어 지울 비트가 없다(2026-11-17).
     */
    private static final byte[] MERGE_AND_SWAP = """
            if redis.call('STRLEN', KEYS[2]) <= tonumber(ARGV[1]) then
              redis.call('BITOP', 'OR', KEYS[1], KEYS[1], KEYS[2])
            end
            return redis.call('RENAME', KEYS[1], KEYS[2])
            """.getBytes(StandardCharsets.UTF_8);

    private final RedisConnectionFactory connectionFactory;
    private static final int PIPELINE_CHUNK = 5_000;

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
    public void add(String sha256) {
        try {
            long[] positions = positions(sha256);
            execute(connection -> {
                connection.openPipeline();
                for (long position : positions) {
                    connection.setBit(KEY, position, true);
                }
                connection.closePipeline();
                return null;
            });
        } catch (RuntimeException e) {
            // 블룸 누락은 다음 재구축이 메운다 — 호출한 쓰기(목록 등록/업로드)를 실패시키지 않는다.
            log.warn("블룸 필터 즉시 추가 실패(다음 재구축에서 반영): {}", e.toString());
        }
    }

    @Override
    public void rebuild(List<String> cachedHashes) {
        // 비트 배열을 메모리에서 만든 뒤 SET 한 번으로 쓴다. 해시마다 SETBIT을 파이프라인에 쌓으면 100만 건에서
        // 명령 수백만 개와 응답 대기 객체가 생겨 Redis 시간 초과와 힙 고갈로 서버가 멈췄다(일괄 가져오기 리허설).
        // 크기는 bitSize/8 바이트뿐이다(기대 150만 건, 오탐 1%면 약 1.8MB).
        byte[] bits = new byte[(int) ((bitSize + 7) / 8)];
        for (String sha256 : cachedHashes) {
            for (long position : positions(sha256)) {
                // Redis 비트 순서: 오프셋 0이 첫 바이트의 최상위 비트다(SETBIT/GETBIT과 같게).
                bits[(int) (position >>> 3)] |= (byte) (0x80 >>> (position & 7));
            }
        }
        // 호출마다 고유한 임시 키를 쓴다 — 기동 시 1차 빌드와 수동 재구축이 겹쳐도 서로 밟지 않는다.
        byte[] rebuildingKey = (REBUILDING_KEY_PREFIX + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        byte[] length = String.valueOf(bits.length).getBytes(StandardCharsets.UTF_8);
        execute(connection -> {
            connection.stringCommands().set(rebuildingKey, bits);
            connection.scriptingCommands().eval(MERGE_AND_SWAP, ReturnType.STATUS, 2, rebuildingKey, KEY, length);
            return null;
        });
    }

    private <T> T execute(Function<RedisConnection, T> action) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return action.apply(connection);
        }
    }

    @Override
    public void addAll(Collection<String> sha256s) {
        if (sha256s.isEmpty()) {
            return;
        }
        try {
            // 파이프라인 하나에 너무 많이 쌓지 않도록 나눠 보낸다(재구축 뒤 목록 재반영은 수십만 건이다).
            List<String> all = List.copyOf(sha256s);
            for (int from = 0; from < all.size(); from += PIPELINE_CHUNK) {
                List<long[]> positions = all.subList(from, Math.min(all.size(), from + PIPELINE_CHUNK)).stream()
                        .map(this::positions).toList();
                execute(connection -> {
                    connection.openPipeline();
                    for (long[] each : positions) {
                        for (long position : each) {
                            connection.setBit(KEY, position, true);
                        }
                    }
                    connection.closePipeline();
                    return null;
                });
            }
        } catch (RuntimeException e) {
            log.warn("블룸 필터 일괄 추가 실패 {}건(다음 재구축에서 반영): {}", sha256s.size(), e.toString());
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
