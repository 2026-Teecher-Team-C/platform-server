package com.teecherteamc.platform.event.application;

import java.util.UUID;

/** 차단(BLOCKED·FAIL_CLOSE)이 아닌 이벤트는 확인 대상이 아니다. 컨트롤러가 409로 바꾼다. */
public class NotBlockedEventException extends RuntimeException {

    public NotBlockedEventException(UUID eventId) {
        super("download event is not blocked: " + eventId);
    }
}
