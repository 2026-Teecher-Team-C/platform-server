package com.teecherteamc.platform.verdict.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 스캔하면서 임계값 이하인 이웃 중 가장 가까운 limit개만 모으는 수집기. 메모리는 limit에 비례해 일정하다.
 *
 * <p>가장 먼 것을 바로 꺼낼 수 있도록 최대 힙에 limit+1개까지만 담는다. limit+1번째가 남아 있으면
 * limit을 넘는 이웃이 더 있었다는 뜻이라 truncated로 알린다(추가 스캔 없음).
 * 순서는 거리 오름차순, 같으면 sha256 오름차순이라 스캔 순서나 DB 계획이 달라도 결과가 같다
 * (기존 JdbcSimilarityDecisionStore.compareTlsh의 동률 규칙과 같다).
 *
 * <p>한 번 쓰고 버리는 객체이며 스레드 안전하지 않다. excludeSha256은 정규화된(소문자) 값으로 받는다.
 */
public final class NearestSimilarFiles {

    private static final Comparator<SimilarFile> NEAREST_FIRST =
            Comparator.comparingInt(SimilarFile::distance).thenComparing(SimilarFile::sha256);

    private final int maxDistance;
    private final int limit;
    private final String excludeSha256;
    private final PriorityQueue<SimilarFile> farthestFirst = new PriorityQueue<>(NEAREST_FIRST.reversed());

    /** excludeSha256이 null이면 아무것도 제외하지 않는다. */
    public NearestSimilarFiles(int maxDistance, int limit, String excludeSha256) {
        if (maxDistance < 0) {
            throw new IllegalArgumentException("maxDistance는 0 이상이어야 합니다: " + maxDistance);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit은 1 이상이어야 합니다: " + limit);
        }
        this.maxDistance = maxDistance;
        this.limit = limit;
        this.excludeSha256 = excludeSha256;
    }

    public void offer(String sha256, int distance) {
        if (distance > maxDistance || sha256.equals(excludeSha256)) {
            return;
        }
        farthestFirst.add(new SimilarFile(sha256, distance));
        if (farthestFirst.size() > (long) limit + 1) {
            farthestFirst.poll();
        }
    }

    /** limit을 넘는 이웃이 더 있었으면 true. */
    public boolean truncated() {
        return farthestFirst.size() > limit;
    }

    /** 가까운 순 최대 limit개. 호출할 때마다 새 불변 리스트를 만든다. */
    public List<SimilarFile> files() {
        List<SimilarFile> sorted = new ArrayList<>(farthestFirst);
        sorted.sort(NEAREST_FIRST);
        return List.copyOf(sorted.size() > limit ? sorted.subList(0, limit) : sorted);
    }
}
