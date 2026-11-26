package com.teecherteamc.platform.lookup.infrastructure;

import com.teecherteamc.platform.lookup.application.BloomRebuildRequests;
import com.teecherteamc.platform.lookup.domain.BloomFillStats;
import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import com.teecherteamc.platform.lookup.domain.BloomRebuildResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
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
 * 의존성(Guava/MurmurHash) 없이 TokenHash와 같은 MessageDigest만 쓴다. 추가와 재구축이 같은 위치 함수를 쓴다.
 *
 * <p>깨끗한 재구축 + 이중 쓰기. 재구축은 옛 live와 OR하지 않고 스냅숏만으로 새 비트맵을 만들어 교체하므로 원천에서 빠진
 * 해시의 비트가 지워진다. 순서: (1) 재구축마다 토큰(UUID)을 정해 표시 키 {@code <live>:pending}에 {@code SET NX PX}로
 * 쓰고(이미 있으면 다른 재구축이 도는 중이라 건너뛴다), 이중 쓰기 비트맵 {@code <live>:pending:<token>}을 빈 값으로
 * 만든다(같은 TTL). (2) 그 뒤에 DB 스냅숏을 읽어 메모리 비트 배열을 만들고 임시 키에 SET한다. (3) Lua 한 번에, 표시 값이
 * 자기 토큰일 때만 임시 키를 자기 pending 비트맵과 OR → live로 RENAME → 비트맵과 표시 삭제. 추가(add/addAll)는 Lua 한
 * 번에 live에 SETBIT하고(live가 없으면 만들지 않고 missing 재구축을 요청한다), 표시가 있으면 그 토큰의 비트맵에도
 * SETBIT한다.
 *
 * <p>왜 빠짐이 없는가: 추가는 DB 커밋 <b>뒤</b>에 일어난다. 추가가 (1) 전이면 커밋도 (1) 전이라 (2)의 스냅숏에 들어 있다.
 * (1) 뒤면 표시가 그대로인 한 자기 비트맵에 같이 쓰여 (3)에서 합쳐진다. (3)은 원자적이라 그 사이에 끼는 추가가 없다. 표시가
 * (3) 전에 사라졌거나(TTL 만료) 다른 재구축의 토큰으로 바뀌었으면 그동안의 추가가 자기 비트맵에 있다는 보장이 없으므로
 * 교체하지 않는다. 토큰 덕분에 늦게 끝난 재구축이 남의 표시로 교체하거나 남의 표시를 지우지 않는다. 재구축이 중간에 죽으면
 * 표시와 비트맵은 TTL로 사라지고 live는 그대로다.
 */
@Component
class RedisBloomFilterPortImpl implements BloomFilterPort {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RedisBloomFilterPortImpl.class);
    private static final String KEY_PREFIX = "bloom:file_verdicts:m";
    private static final String PENDING_SUFFIX = ":pending";
    private static final String REBUILDING_SUFFIX = ":rebuilding:";
    private static final String UNION_SUFFIX = ":union:";
    /** 재구축 하나가 이보다 오래 걸리면 표시가 사라져 교체를 포기한다. 지금 재구축은 수 초다. */
    private static final long PENDING_TTL_MS = 600_000;
    /**
     * KEYS[1]=새 비트맵 B, KEYS[2]=live, KEYS[3]=표시, KEYS[4]=합집합 임시 키, KEYS[5]=자기 pending 비트맵,
     * ARGV[1]=자기 토큰. 표시 값이 자기 토큰이 아니거나 비트맵이 없으면(이중 쓰기 보장이 깨짐) B를 지우고 교체하지 않는다.
     * 있으면 B |= 비트맵, 지운 비트 수 = BITCOUNT(B | live) - BITCOUNT(B), B를 live로 RENAME, 비트맵·표시 삭제.
     * 반환: {swapped(0/1), setBits, clearedBits}.
     */
    private static final byte[] SWAP_SCRIPT = """
            if redis.call('GET', KEYS[3]) ~= ARGV[1] or redis.call('EXISTS', KEYS[5]) == 0 then
              redis.call('DEL', KEYS[1])
              return {0, 0, 0}
            end
            redis.call('BITOP', 'OR', KEYS[1], KEYS[1], KEYS[5])
            local cleared = 0
            if redis.call('EXISTS', KEYS[2]) == 1 then
              redis.call('BITOP', 'OR', KEYS[4], KEYS[1], KEYS[2])
              cleared = redis.call('BITCOUNT', KEYS[4]) - redis.call('BITCOUNT', KEYS[1])
              redis.call('DEL', KEYS[4])
            end
            local setBits = redis.call('BITCOUNT', KEYS[1])
            redis.call('RENAME', KEYS[1], KEYS[2])
            redis.call('DEL', KEYS[5], KEYS[3])
            return {1, setBits, cleared}
            """.getBytes(StandardCharsets.UTF_8);

    /**
     * KEYS[1]=표시, KEYS[2]=자기 pending 비트맵, ARGV[1]=토큰, ARGV[2]=TTL(ms). 표시를 NX로 세우고 성공하면 비트맵을 같은
     * TTL로 만든다. 한 스크립트라 표시와 비트맵 사이에 추가가 끼어 비트맵을 TTL 없이 만들거나 그 비트를 덮어쓰는 일이 없다.
     * SETBIT은 TTL을 유지하므로 재구축이 죽으면 둘 다 사라진다.
     */
    private static final byte[] BEGIN_SCRIPT = """
            if not redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 0 end
            redis.call('SET', KEYS[2], '', 'PX', ARGV[2])
            return 1
            """.getBytes(StandardCharsets.UTF_8);

    /** KEYS[1]=표시, ARGV[1]=자기 토큰. 표시가 자기 것일 때만 지운다 — TTL 만료 뒤 다른 재구축이 세운 표시를 지우지 않는다. */
    private static final byte[] RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """.getBytes(StandardCharsets.UTF_8);

    private final RedisConnectionFactory connectionFactory;
    private static final int PIPELINE_CHUNK = 5_000;

    private static final byte[] ADD_SCRIPT = """
            -- 이중 쓰기 대상은 표시 값(토큰)으로 정해지는 동적 키다. KEYS로 미리 선언하지 않으므로 Redis Cluster에서는
            -- 안 되지만, 단일 노드 Redis만 쓴다.
            -- live가 없으면 live에는 쓰지 않는다: 새로 만들면 이 해시만 든 부분 키가 생겨 다른 목록 해시가 재구축 전까지
            -- "없음"으로 나온다(거짓 음성). pending 비트맵에는 그대로 써서 진행 중인 재구축이 교체 때 합친다.
            -- 반환: live가 없었으면 1(호출 쪽이 재구축을 요청), 있었으면 0.
            local token = redis.call('GET', KEYS[2])
            local pending = token and (KEYS[2] .. ':' .. token)
            local live = redis.call('EXISTS', KEYS[1]) == 1
            for i = 1, #ARGV do
              if live then redis.call('SETBIT', KEYS[1], ARGV[i], 1) end
              if pending then redis.call('SETBIT', pending, ARGV[i], 1) end
            end
            if live then return 0 end
            return 1
            """.getBytes(StandardCharsets.UTF_8);

    private final long bitSize;
    private final int hashCount;
    private final BloomRebuildRequests requests;
    private final String liveKey;
    private final byte[] key;
    private final byte[] pendingKey;

    RedisBloomFilterPortImpl(
            RedisConnectionFactory connectionFactory,
            @Value("${lookup.bloom-filter.expected-hash-count}") long expectedHashCount,
            @Value("${lookup.bloom-filter.false-positive-rate}") double falsePositiveRate,
            BloomRebuildRequests requests) {
        this.connectionFactory = connectionFactory;
        this.requests = requests;
        this.bitSize = Math.max(
                1, (long) Math.ceil(-(expectedHashCount * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2))));
        this.hashCount = Math.max(1, (int) Math.round((double) bitSize / expectedHashCount * Math.log(2)));
        this.liveKey = KEY_PREFIX + bitSize;
        this.key = liveKey.getBytes(StandardCharsets.UTF_8);
        this.pendingKey = (liveKey + PENDING_SUFFIX).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String keyName() {
        return liveKey;
    }

    @Override
    public boolean mightContain(String sha256) {
        long[] positions = positions(sha256);
        return execute(connection -> {
            // k번 GETBIT을 한 번씩 왕복하는 대신 파이프라인으로 묶어서 한 번만 왕복한다 — 다운로드마다
            // 호출되는 가장 뜨거운 읽기 경로라 지연에 민감하다.
            connection.openPipeline();
            for (long position : positions) {
                connection.getBit(key, position);
            }
            connection.keyCommands().exists(key);
            List<Object> results = connection.closePipeline();
            // 마지막 결과가 EXISTS다. 키가 없으면 판단 근거가 없으니 "있을 수 있음"으로 답하고 재구축을 요청한다.
            if (!(results.getLast() instanceof Boolean exists) || !exists) {
                requests.request(BloomRebuildRequests.MISSING);
                return true;
            }
            for (Object result : results.subList(0, results.size() - 1)) {
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
            if (execute(connection -> evalAdd(connection, positions))) {
                requests.request(BloomRebuildRequests.MISSING);
            }
        } catch (RuntimeException e) {
            // 호출한 쓰기(목록 등록/업로드)를 실패시키지 않는다. 누락은 재구축이 메운다.
            log.warn("블룸 필터 즉시 추가 실패(재구축 요청): {}", e.toString());
            requests.request(BloomRebuildRequests.ADD_FAILED);
        }
    }

    /**
     * live와(재구축 중이면 그 재구축의 pending 비트맵에도) 위치들을 한 번에 켠다. live가 없으면 live는 만들지 않고 true를
     * 준다 — 호출 쪽이 missing 재구축을 요청한다.
     */
    private boolean evalAdd(RedisConnection connection, long[] positions) {
        byte[][] keysAndArgs = new byte[2 + positions.length][];
        keysAndArgs[0] = key;
        keysAndArgs[1] = pendingKey;
        for (int i = 0; i < positions.length; i++) {
            keysAndArgs[2 + i] = Long.toString(positions[i]).getBytes(StandardCharsets.UTF_8);
        }
        Long liveMissing = connection.scriptingCommands().eval(ADD_SCRIPT, ReturnType.INTEGER, 2, keysAndArgs);
        return Long.valueOf(1).equals(liveMissing);
    }

    @Override
    public Optional<BloomRebuild> beginRebuild() {
        RedisBloomRebuild rebuild = new RedisBloomRebuild();
        Long started = execute(connection -> connection.scriptingCommands().eval(
                BEGIN_SCRIPT, ReturnType.INTEGER, 2, pendingKey, rebuild.pendingBitmapKey, rebuild.token,
                Long.toString(PENDING_TTL_MS).getBytes(StandardCharsets.UTF_8)));
        return Long.valueOf(1).equals(started) ? Optional.of(rebuild) : Optional.empty();
    }

    @Override
    public String serverRunId() {
        try {
            Properties info = execute(connection -> connection.serverCommands().info("server"));
            return info == null ? null : info.getProperty("run_id");
        } catch (RuntimeException e) {
            log.debug("Redis run_id 조회 실패: {}", e.toString());
            return null;
        }
    }

    @Override
    public Optional<BloomFillStats> fillStats() {
        return execute(connection -> {
            connection.openPipeline();
            connection.keyCommands().exists(key);
            connection.stringCommands().bitCount(key);
            List<Object> results = connection.closePipeline();
            if (!(results.get(0) instanceof Boolean exists) || !exists) {
                return Optional.empty();
            }
            return Optional.of(new BloomFillStats((Long) results.get(1), bitSize, hashCount));
        });
    }

    /**
     * begin 이후의 재구축 한 건. complete가 끝까지 가지 못하고 닫히면 자기 pending 비트맵과 임시 키를 지우고, 표시는
     * 자기 토큰일 때만 지운다.
     */
    private final class RedisBloomRebuild implements BloomRebuild {

        private final String id = UUID.randomUUID().toString();
        private final byte[] token = id.getBytes(StandardCharsets.UTF_8);
        private final byte[] pendingBitmapKey = (liveKey + PENDING_SUFFIX + ":" + id).getBytes(StandardCharsets.UTF_8);
        private final byte[] rebuildingKey = (liveKey + REBUILDING_SUFFIX + id).getBytes(StandardCharsets.UTF_8);
        private final byte[] unionKey = (liveKey + UNION_SUFFIX + id).getBytes(StandardCharsets.UTF_8);
        private boolean finished;

        @Override
        public BloomRebuildResult complete(List<String> hashes) {
            // 비트 배열을 메모리에서 만든 뒤 SET 한 번으로 쓴다. 해시마다 SETBIT을 파이프라인에 쌓으면 100만 건에서
            // 명령 수백만 개와 응답 대기 객체가 생겨 Redis 시간 초과와 힙 고갈로 서버가 멈췄다(일괄 가져오기 리허설).
            // 크기는 bitSize/8 바이트뿐이다(기대 150만 건, 오탐 1%면 약 1.8MB).
            byte[] bits = new byte[(int) ((bitSize + 7) / 8)];
            for (String sha256 : hashes) {
                for (long position : positions(sha256)) {
                    // Redis 비트 순서: 오프셋 0이 첫 바이트의 최상위 비트다(SETBIT/GETBIT과 같게).
                    bits[(int) (position >>> 3)] |= (byte) (0x80 >>> (position & 7));
                }
            }
            List<Object> result = execute(connection -> {
                connection.stringCommands().set(rebuildingKey, bits);
                return connection.scriptingCommands().eval(
                        SWAP_SCRIPT, ReturnType.MULTI, 5,
                        rebuildingKey, key, pendingKey, unionKey, pendingBitmapKey, token);
            });
            finished = true;
            return new BloomRebuildResult((Long) result.get(0) == 1, (Long) result.get(1), (Long) result.get(2));
        }

        @Override
        public void close() {
            if (finished) {
                return;
            }
            try {
                execute(connection -> {
                    connection.scriptingCommands().eval(RELEASE_SCRIPT, ReturnType.INTEGER, 1, pendingKey, token);
                    return connection.keyCommands().del(pendingBitmapKey, rebuildingKey);
                });
            } catch (RuntimeException e) {
                // 지우지 못해도 표시와 비트맵은 TTL로 사라진다. 닫기가 원래 예외를 덮지 않게 삼킨다.
                log.warn("블룸 재구축 정리 실패(TTL로 사라짐): {}", e.toString());
            }
        }
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
            boolean liveMissing = false;
            for (int from = 0; from < all.size(); from += PIPELINE_CHUNK) {
                long[] positions = all.subList(from, Math.min(all.size(), from + PIPELINE_CHUNK)).stream()
                        .flatMapToLong(h -> Arrays.stream(positions(h))).toArray();
                liveMissing |= execute(connection -> evalAdd(connection, positions));
            }
            if (liveMissing) {
                requests.request(BloomRebuildRequests.MISSING);
            }
        } catch (RuntimeException e) {
            log.warn("블룸 필터 일괄 추가 실패 {}건(재구축 요청): {}", sha256s.size(), e.toString());
            requests.request(BloomRebuildRequests.ADD_FAILED);
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
