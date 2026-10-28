package com.teecherteamc.platform.event.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * agentId로 호스트명(devices.hostname)만 읽는 포트. SSE 알림에 PC 이름을 붙이려고 fleet 컨텍스트의
 * 애그리거트를 로드하지 않고 한 값만 읽는다({@link ListDownloadEventsPort}와 같은 이유).
 */
public interface FindAgentHostnamePort {

    Optional<String> findHostname(UUID agentId);
}
