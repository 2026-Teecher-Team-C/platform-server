package com.teecherteamc.platform.dashboard.domain;

import java.util.List;

/**
 * 위협 대시보드 위젯용 읽기 전용 집계. 모두 ListDownloadEventsPortImpl과 같은 agents/devices 조인을 거쳐
 * 이벤트 목록에 안 보이는 행이 숫자에 섞이지 않게 한다.
 */
public interface DashboardWidgetsPort {

    /** BLOCKED만. 룰이 여러 개 걸린 이벤트는 모든 룰 줄에 센다. 마지막 탐지 최신순. */
    List<ThreatRow> threats(DashboardPeriod period, int limit);

    /** 기간 안의 차단(BLOCKED·FAIL_CLOSE) 전부. */
    List<BlockEvent> blocks(DashboardPeriod period);

    /** 차단 수 내림차순, 같으면 라벨 이름순. */
    List<TopSourceRow> topSources(DashboardPeriod period, SourceKind by, int limit);

    enum SourceKind { AGENT, HOST }
}
