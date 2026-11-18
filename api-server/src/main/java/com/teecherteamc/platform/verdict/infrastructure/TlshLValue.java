package com.teecherteamc.platform.verdict.infrastructure;

/**
 * TLSH 문자열의 L-value(파일 길이 구간)와 비교 후보 창. 형식은 "T1" + checksum 2자 + L 2자(니블 교환) + Q 2자 + 본문
 * (TlshUtil.from_hex_swapped). DB 함수 tlsh_lvalue()(V19)와 같은 값을 낸다.
 *
 * <p>TLSH totalDiff의 길이 페널티는 원형(mod 256) 차이가 2 이상이면 차이×12다. 차이가 6이면 길이 항만 72라 WARN 상한
 * 70을 넘으므로, ±5 창 밖 후보를 빼도 ≤70 후보는 하나도 놓치지 않는다.
 */
public final class TlshLValue {

    public static final int WINDOW = 5;

    private TlshLValue() {
    }

    public static int of(String tlsh) {
        return Character.digit(tlsh.charAt(5), 16) << 4 | Character.digit(tlsh.charAt(4), 16);
    }

    /** lvalue-5 ~ lvalue+5를 mod 256으로 감싼 11개. */
    public static Integer[] window(int lvalue) {
        Integer[] window = new Integer[WINDOW * 2 + 1];
        for (int i = -WINDOW; i <= WINDOW; i++) {
            window[i + WINDOW] = Math.floorMod(lvalue + i, 256);
        }
        return window;
    }
}
