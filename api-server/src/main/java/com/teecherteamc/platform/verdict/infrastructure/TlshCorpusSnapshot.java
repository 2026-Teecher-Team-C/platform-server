package com.teecherteamc.platform.verdict.infrastructure;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * 한 corpus 토큰 시점의 활성 악성 지문 전체. 지문 35바이트·SHA 32바이트를 평평한 배열에 L-value 순으로 둔다
 * (117만 건 ≈ 80MB). 불변이라 여러 요청이 락 없이 함께 읽는다.
 *
 * <p>{@link #nearest(String)}는 입력 L ±5 묶음만 비교한다. 창 밖은 길이 감점만 72(>70)라 결론에 영향이 없다
 * ({@link TlshLValue}). 거리가 같으면 SHA가 작은 쪽을 고른다(DB 경로와 같은 동률 규칙).
 */
public final class TlshCorpusSnapshot {

    private static final int SHA_LENGTH = 32;
    private static final HexFormat HEX = HexFormat.of();

    public record Match(String sha, String tlsh, int distance) {}

    private final String token;
    private final int size;
    private final byte[] tlsh;
    private final byte[] sha;
    /** L-value l인 항목은 [starts[l], starts[l+1]). */
    private final int[] starts;

    private TlshCorpusSnapshot(String token, int size, byte[] tlsh, byte[] sha, int[] starts) {
        this.token = token;
        this.size = size;
        this.tlsh = tlsh;
        this.sha = sha;
        this.starts = starts;
    }

    public static Builder builder(String token) {
        return new Builder(token);
    }

    public String token() {
        return token;
    }

    public int size() {
        return size;
    }

    /** 창 안에 후보가 없으면 null. 입력은 "T1" + hex 70자여야 한다. */
    public Match nearest(String input) {
        byte[] in = TlshBytes.decode(input);
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (Integer l : TlshLValue.window(TlshBytes.lvalue(in, 0))) {
            for (int i = starts[l], end = starts[l + 1]; i < end; i++) {
                int d = TlshBytes.distance(in, 0, tlsh, i * TlshBytes.LENGTH);
                if (d < bestDistance || (d == bestDistance && compareSha(i, best) < 0)) {
                    best = i;
                    bestDistance = d;
                }
            }
        }
        if (best < 0) {
            return null;
        }
        return new Match(HEX.formatHex(sha, best * SHA_LENGTH, (best + 1) * SHA_LENGTH),
                TlshBytes.encode(tlsh, best * TlshBytes.LENGTH), bestDistance);
    }

    /** SHA는 소문자 hex(V8 CHECK)라 바이트 부호 없는 사전순 = 문자열 순서다. */
    private int compareSha(int i, int j) {
        return Arrays.compareUnsigned(sha, i * SHA_LENGTH, (i + 1) * SHA_LENGTH, sha, j * SHA_LENGTH, (j + 1) * SHA_LENGTH);
    }

    public static final class Builder {
        private final String token;
        private byte[] tlsh = new byte[1024 * TlshBytes.LENGTH];
        private byte[] sha = new byte[1024 * SHA_LENGTH];
        private final int[] counts = new int[256];
        private int size;

        private Builder(String token) {
            this.token = token;
        }

        public Builder add(String sha256, String encoded) {
            if (size * TlshBytes.LENGTH == tlsh.length) {
                tlsh = Arrays.copyOf(tlsh, tlsh.length * 2);
                sha = Arrays.copyOf(sha, sha.length * 2);
            }
            byte[] t = TlshBytes.decode(encoded);
            byte[] s = HEX.parseHex(sha256);
            if (s.length != SHA_LENGTH) {
                throw new IllegalArgumentException("SHA256이 아니다: " + sha256);
            }
            System.arraycopy(t, 0, tlsh, size * TlshBytes.LENGTH, TlshBytes.LENGTH);
            System.arraycopy(s, 0, sha, size * SHA_LENGTH, SHA_LENGTH);
            counts[TlshBytes.lvalue(t, 0)]++;
            size++;
            return this;
        }

        /** L-value 계수 정렬. 빌더 배열은 버린다. */
        public TlshCorpusSnapshot build() {
            int[] starts = new int[257];
            for (int l = 0; l < 256; l++) {
                starts[l + 1] = starts[l] + counts[l];
            }
            int[] next = Arrays.copyOf(starts, 256);
            byte[] sortedTlsh = new byte[size * TlshBytes.LENGTH];
            byte[] sortedSha = new byte[size * SHA_LENGTH];
            for (int i = 0; i < size; i++) {
                int to = next[TlshBytes.lvalue(tlsh, i * TlshBytes.LENGTH)]++;
                System.arraycopy(tlsh, i * TlshBytes.LENGTH, sortedTlsh, to * TlshBytes.LENGTH, TlshBytes.LENGTH);
                System.arraycopy(sha, i * SHA_LENGTH, sortedSha, to * SHA_LENGTH, SHA_LENGTH);
            }
            return new TlshCorpusSnapshot(token, size, sortedTlsh, sortedSha, starts);
        }
    }
}
