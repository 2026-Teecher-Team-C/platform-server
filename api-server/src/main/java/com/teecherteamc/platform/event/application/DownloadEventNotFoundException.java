package com.teecherteamc.platform.event.application;

import java.util.UUID;

/** 확인하려는 다운로드 이벤트가 없다. 컨트롤러가 404로 바꾼다. */
public class DownloadEventNotFoundException extends RuntimeException {

    public DownloadEventNotFoundException(UUID eventId) {
        super("download event not found: " + eventId);
    }
}
