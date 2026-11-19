package com.teecherteamc.platform.verdict.infrastructure;

/**
 * "T1" + 대문자 hex 70자 지문(V8 CHECK)의 35바이트 표현과 거리. 메모리 corpus가 요청마다 문자열을 파싱하지 않게 한다.
 *
 * <p>바이트 i는 hex 두 글자를 그대로 읽은 값이다: 0 = 체크섬, 1 = L-value(니블 교환), 2 = Q1(상위)·Q2(하위),
 * 3~34 = 본문(인코딩 때 뒤집힌 순서 그대로). 거리는 위치별 합이라 본문 순서를 되돌릴 필요가 없다.
 * 규칙은 {@code com.trendmicro.tlsh.Tlsh#totalDiff(Tlsh, boolean)}(len_diff=true)와 같다.
 */
public final class TlshBytes {

    public static final int LENGTH = 35;
    private static final int BODY_OFFSET = 3;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    /** 바이트 하나(2비트 코드 4개) 쌍의 본문 거리. 코드 차이 0·1·2는 그대로, 3은 6(라이브러리 bit_pairs_diff_table). */
    private static final byte[] BODY_DIFF = bodyDiffTable();

    private TlshBytes() {
    }

    public static byte[] decode(String tlsh) {
        if (tlsh.length() != 2 + LENGTH * 2 || tlsh.charAt(0) != 'T' || tlsh.charAt(1) != '1') {
            throw new IllegalArgumentException("T1 + hex 70자가 아니다: " + tlsh);
        }
        byte[] bytes = new byte[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            int hi = Character.digit(tlsh.charAt(2 + i * 2), 16);
            int lo = Character.digit(tlsh.charAt(3 + i * 2), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("hex가 아니다: " + tlsh);
            }
            bytes[i] = (byte) (hi << 4 | lo);
        }
        return bytes;
    }

    public static String encode(byte[] bytes, int offset) {
        char[] chars = new char[2 + LENGTH * 2];
        chars[0] = 'T';
        chars[1] = '1';
        for (int i = 0; i < LENGTH; i++) {
            int b = bytes[offset + i] & 0xFF;
            chars[2 + i * 2] = HEX[b >>> 4];
            chars[3 + i * 2] = HEX[b & 0xF];
        }
        return new String(chars);
    }

    /** {@link TlshLValue#of(String)}와 같은 값(문자열의 L 두 글자는 니블이 바뀌어 있다). */
    public static int lvalue(byte[] bytes, int offset) {
        int b = bytes[offset + 1] & 0xFF;
        return (b & 0xF) << 4 | b >>> 4;
    }

    public static int distance(byte[] a, int aOffset, byte[] b, int bOffset) {
        int ldiff = modDiff(lvalue(a, aOffset), lvalue(b, bOffset), 256);
        int diff = ldiff <= 1 ? ldiff : ldiff * 12;
        int qa = a[aOffset + 2] & 0xFF;
        int qb = b[bOffset + 2] & 0xFF;
        diff += qPenalty(modDiff(qa >>> 4, qb >>> 4, 16));
        diff += qPenalty(modDiff(qa & 0xF, qb & 0xF, 16));
        if (a[aOffset] != b[bOffset]) {
            diff++;
        }
        for (int i = BODY_OFFSET; i < LENGTH; i++) {
            diff += BODY_DIFF[(a[aOffset + i] & 0xFF) << 8 | (b[bOffset + i] & 0xFF)];
        }
        return diff;
    }

    private static int qPenalty(int qdiff) {
        return qdiff <= 1 ? qdiff : (qdiff - 1) * 12;
    }

    private static int modDiff(int x, int y, int range) {
        int d = Math.abs(x - y);
        return Math.min(d, range - d);
    }

    private static byte[] bodyDiffTable() {
        byte[] table = new byte[256 * 256];
        for (int i = 0; i < 256; i++) {
            for (int j = 0; j < 256; j++) {
                int diff = 0;
                for (int shift = 0; shift < 8; shift += 2) {
                    int d = Math.abs((i >>> shift & 3) - (j >>> shift & 3));
                    diff += d == 3 ? 6 : d;
                }
                table[i << 8 | j] = (byte) diff;
            }
        }
        return table;
    }
}
