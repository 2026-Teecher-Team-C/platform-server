package com.teecherteamc.platform.event.domain;

/**
 * file_verdicts(sha256) 존재 여부만 확인하는 포트. verdict 컨텍스트 소유 테이블을 읽기 전용으로 참조한다.
 *
 * <p>download_events.sha256 -&gt; file_verdicts(sha256) FK 때문에 필요하다: 화이트리스트 히트 등으로
 * file_verdicts에 행이 없는 해시를 ReportEvent가 보고하면 FK 위반이 난다. CheckHash가 아직 없는 지금
 * 시점에는 이 확인으로 null 처리 여부를 결정한다.
 */
public interface FileVerdictExistsPort {

    boolean exists(String sha256);
}
