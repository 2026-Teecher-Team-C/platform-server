package com.teecherteamc.platform.dashboard.domain;

import java.util.List;

/** 확인 안 한 높은 심각도(HIGH·CRITICAL) 차단. 기간 필터 없이 최신순이다. */
public interface UnacknowledgedBlocksPort {

    List<UnacknowledgedBlockRow> list(long offset, int limit);

    long count();
}
