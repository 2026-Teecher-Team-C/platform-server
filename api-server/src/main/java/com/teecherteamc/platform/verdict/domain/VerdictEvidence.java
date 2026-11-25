package com.teecherteamc.platform.verdict.domain;

import java.util.List;
import java.util.Set;

/**
 * 차단 응답에 싣는 구조화된 판정 근거. kind에 해당하는 값만 채우고 나머지는 빈 값이다.
 * 차단 페이지·콘솔이 "왜 막혔는지"를 말로 보여 주는 데 쓴다(proto {@code VerdictEvidence}).
 */
public record VerdictEvidence(
        Kind kind,
        String label,
        String family,
        Integer tlshDistance,
        String matchedSampleSha256,
        List<Rule> rules,
        String previousVerdict) {

    /** 출처 이름만 있는 사유는 패밀리가 아니다(예: 라벨 없는 MalwareBazaar 항목의 사유 "MalwareBazaar"). */
    private static final Set<String> SOURCE_NAMES = Set.of("MalwareBazaar");
    private static final String SOURCE_SEPARATOR = ": ";

    public enum Kind { NONE, BLACKLIST, SIMILARITY, YARA, CACHE }

    public record Rule(String name, String severity) {
    }

    public VerdictEvidence {
        label = label == null ? "" : label;
        family = family == null ? "" : family;
        matchedSampleSha256 = matchedSampleSha256 == null ? "" : matchedSampleSha256;
        rules = rules == null ? List.of() : List.copyOf(rules);
        previousVerdict = previousVerdict == null ? "" : previousVerdict;
    }

    public static VerdictEvidence none() {
        return new VerdictEvidence(Kind.NONE, null, null, null, null, null, null);
    }

    public static VerdictEvidence blacklist(String label) {
        return new VerdictEvidence(Kind.BLACKLIST, label, familyOf(label), null, null, null, null);
    }

    public static VerdictEvidence similarity(int distance, String sampleSha256, String sampleLabel) {
        return new VerdictEvidence(Kind.SIMILARITY, sampleLabel, familyOf(sampleLabel), distance, sampleSha256,
                null, null);
    }

    public static VerdictEvidence yara(List<Rule> rules) {
        return new VerdictEvidence(Kind.YARA, null, null, null, null, rules, null);
    }

    public static VerdictEvidence cache(String previousVerdict) {
        return new VerdictEvidence(Kind.CACHE, null, null, null, null, null, previousVerdict);
    }

    /** "출처: 패밀리"면 패밀리, 접두가 없으면 사유 전체. 출처 이름뿐이거나 비면 빈 문자열. */
    public static String familyOf(String label) {
        if (label == null) {
            return "";
        }
        int at = label.indexOf(SOURCE_SEPARATOR);
        String family = (at >= 0 ? label.substring(at + SOURCE_SEPARATOR.length()) : label).strip();
        return SOURCE_NAMES.contains(family) ? "" : family;
    }
}
