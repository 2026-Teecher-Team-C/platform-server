package com.teecherteamc.platform.verdict.domain;

import java.util.List;

/**
 * 유사 파일 검색 결과. 빈 목록과 "기준 지문이 없어 검색을 못 함"을 status로 구분한다.
 * 빈 목록(OK)은 "임계값 이하인 이웃이 없다"는 뜻이고, 나머지 두 상태는 검색 자체를 못 한 것이다.
 *
 * <p>files는 거리 오름차순(같으면 sha256 오름차순)이다. truncated가 true이면 limit을 넘는 이웃이 더 있었다는 뜻이다.
 * scanned는 읽어서 거리를 계산한 지문 수로, 운영에서 스캔 비용을 재는 값이다.
 */
public record SimilarFiles(Status status, String referenceSha256, int maxDistance, List<SimilarFile> files,
        boolean truncated, long scanned) {

    public enum Status { OK, NO_REFERENCE_FINGERPRINT, INVALID_TLSH }

    public SimilarFiles {
        files = List.copyOf(files);
    }

    /** referenceSha256은 TLSH로 직접 조회했으면 null이다. */
    public static SimilarFiles ok(String referenceSha256, int maxDistance, List<SimilarFile> files,
            boolean truncated, long scanned) {
        return new SimilarFiles(Status.OK, referenceSha256, maxDistance, files, truncated, scanned);
    }

    /** sha256에 계산된(CALCULATED) 지문이 없어 비교 기준을 만들 수 없다. */
    public static SimilarFiles noReferenceFingerprint(String referenceSha256, int maxDistance) {
        return new SimilarFiles(Status.NO_REFERENCE_FINGERPRINT, referenceSha256, maxDistance, List.of(), false, 0);
    }

    /** 입력 TLSH 문자열을 해석할 수 없다. */
    public static SimilarFiles invalidTlsh(int maxDistance) {
        return new SimilarFiles(Status.INVALID_TLSH, null, maxDistance, List.of(), false, 0);
    }
}
