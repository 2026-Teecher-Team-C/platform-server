package com.teecherteamc.platform.lookup.domain;

/**
 * 재구축 한 번의 결과.
 *
 * @param swapped false면 교체 직전에 pending(재구축 중 표시)이 사라져(TTL 만료 등) 교체를 포기했다는 뜻이다. live는 그대로다.
 * @param setBits 새 live에 켜진 비트 수.
 * @param clearedBits 옛 live에는 켜져 있었지만 새 live에는 없는 비트 수 — 이번 재구축이 지운 비트.
 */
public record BloomRebuildResult(boolean swapped, long setBits, long clearedBits) {

    public static BloomRebuildResult notSwapped() {
        return new BloomRebuildResult(false, 0, 0);
    }
}
