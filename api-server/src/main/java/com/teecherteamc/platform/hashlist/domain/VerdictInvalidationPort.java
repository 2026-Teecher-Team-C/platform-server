package com.teecherteamc.platform.hashlist.domain;

/**
 * 해시의 기존 판정(file_verdicts)을 더 이상 쓰지 않게 표시하는 포트.
 * 화이트리스트에 등록한 해시가 예전 차단 판정 캐시로 다시 막히지 않게 한다.
 */
public interface VerdictInvalidationPort {

    /** 그 해시의 판정 행이 있으면 is_stale=true로 바꾼다. 없으면 아무 일도 하지 않는다. */
    void markStale(String sha256);
}
