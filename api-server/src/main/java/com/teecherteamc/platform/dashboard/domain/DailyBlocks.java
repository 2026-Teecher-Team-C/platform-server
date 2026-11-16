package com.teecherteamc.platform.dashboard.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 일별 차단. 이벤트 하나는 한 칸에만 세므로 모든 칸의 합 = 기간 안의 차단 수다.
 * 계산 기준은 콘솔 mock(computeDailyBlocks)과 같다.
 *
 * <ul>
 *   <li>SOURCE: ENGINE, CACHE, BLACKLIST, (그 밖에 실제로 있는 출처: SIMILARITY 등), FAIL_CLOSE</li>
 *   <li>RULE: 대표 룰 차단 수 상위 5개, BLACKLIST, OTHER, FAIL_CLOSE. 룰 없는 차단(TLSH 유사도 등)은 OTHER</li>
 * </ul>
 *
 * @param days 기간의 첫날부터 마지막 날까지(tz 기준) 빠짐없이. 차단 없는 날은 0
 */
public record DailyBlocks(StackBy stackBy, List<String> keys, List<Day> days) {

    public enum StackBy { SOURCE, RULE }

    public record Day(String date, Map<String, Long> counts) {
    }

    static final String BLACKLIST = "BLACKLIST";
    static final String OTHER = "OTHER";
    static final String FAIL_CLOSE = "FAIL_CLOSE";
    private static final List<String> BASE_SOURCES = List.of("ENGINE", "CACHE", BLACKLIST);
    private static final Set<String> RESERVED_RULE_KEYS = Set.of(BLACKLIST, OTHER, FAIL_CLOSE);
    private static final int TOP_RULES = 5;

    public static DailyBlocks compute(List<BlockEvent> blocks, DashboardPeriod period, ZoneId zone, StackBy stackBy) {
        List<String> keys;
        Function<BlockEvent, String> keyOf;
        if (stackBy == StackBy.SOURCE) {
            keyOf = DailyBlocks::sourceKey;
            Set<String> extra = new TreeSet<>();
            blocks.stream().map(keyOf).filter(k -> !BASE_SOURCES.contains(k) && !FAIL_CLOSE.equals(k))
                    .forEach(extra::add);
            keys = new ArrayList<>(BASE_SOURCES);
            keys.addAll(extra);
            keys.add(FAIL_CLOSE);
        } else {
            List<String> topRules = topRules(blocks);
            keyOf = e -> {
                if (e.failClose()) {
                    return FAIL_CLOSE;
                }
                if (BLACKLIST.equals(e.decisionSource())) {
                    return BLACKLIST;
                }
                return topRules.contains(e.topRuleName()) ? e.topRuleName() : OTHER;
            };
            keys = new ArrayList<>(topRules);
            keys.addAll(List.of(BLACKLIST, OTHER, FAIL_CLOSE));
        }

        Map<LocalDate, Map<String, Long>> byDate = new LinkedHashMap<>();
        LocalDate first = LocalDate.ofInstant(period.from(), zone);
        LocalDate last = LocalDate.ofInstant(period.to().minusNanos(1), zone);
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            Map<String, Long> counts = new LinkedHashMap<>();
            keys.forEach(k -> counts.put(k, 0L));
            byDate.put(date, counts);
        }
        for (BlockEvent e : blocks) {
            Map<String, Long> counts = byDate.get(LocalDate.ofInstant(e.createdAt(), zone));
            if (counts != null) {
                counts.merge(keyOf.apply(e), 1L, Long::sum);
            }
        }
        List<Day> days = byDate.entrySet().stream()
                .map(entry -> new Day(entry.getKey().toString(), entry.getValue()))
                .toList();
        return new DailyBlocks(stackBy, List.copyOf(keys), days);
    }

    private static String sourceKey(BlockEvent e) {
        if (e.failClose()) {
            return FAIL_CLOSE;
        }
        return e.decisionSource() == null ? OTHER : e.decisionSource();
    }

    /** 대표 룰 차단 수 상위 5개(같으면 이름순). 예약 키와 이름이 같은 룰은 OTHER로 보내 칸이 겹치지 않게 한다. */
    private static List<String> topRules(List<BlockEvent> blocks) {
        Map<String, Long> counts = new HashMap<>();
        for (BlockEvent e : blocks) {
            String rule = e.topRuleName();
            if (!e.failClose() && !BLACKLIST.equals(e.decisionSource())
                    && rule != null && !RESERVED_RULE_KEYS.contains(rule)) {
                counts.merge(rule, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())))
                .limit(TOP_RULES)
                .map(Map.Entry::getKey)
                .toList();
    }
}
