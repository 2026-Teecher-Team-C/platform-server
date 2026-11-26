package com.teecherteamc.platform.appeal.application;

/**
 * 이 토큰의 차단 기록(download_events)을 아직 받지 못했거나, 토큰의 에이전트가 그 차단 기록의 주인이 아니다.
 * ReportEvent는 차단 응답 뒤에 오므로 잠시 뒤 다시 시도하면 접수될 수 있다. 409로 바꾸는 일은 컨트롤러가 맡는다.
 */
public class AppealNotReadyException extends RuntimeException {

    public AppealNotReadyException() {
        super("차단 기록을 아직 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
