package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 차단 응답에 붙일 소명 링크를 만든다. 비밀키나 base-url이 없으면 링크만 꺼지고 서버는 정상 기동한다. */
@Component
public class AppealLinks {

    private static final Logger log = LoggerFactory.getLogger(AppealLinks.class);

    private final AppealTokens tokens;
    private final String baseUrl;

    public AppealLinks(
            @Value("${platform.appeal.secret:}") String secret,
            @Value("${platform.appeal.base-url:}") String baseUrl,
            @Value("${platform.appeal.ttl:7d}") Duration ttl,
            Clock clock,
            JsonMapper jsonMapper) {
        this.tokens = new AppealTokens(secret, ttl, clock, jsonMapper);
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
        if (!enabled()) {
            log.warn("소명 링크 꺼짐: APPEAL_TOKEN_SECRET(32바이트 이상)과 APPEAL_BASE_URL이 필요합니다");
        }
    }

    public AppealTokens tokens() {
        return tokens;
    }

    public String urlFor(UUID eventId, String sha256, UUID agentId, VerdictEvidence evidence) {
        if (!enabled()) {
            return "";
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/appeal/" + tokens.issue(eventId, sha256, agentId, evidence);
    }

    private boolean enabled() {
        return !baseUrl.isBlank() && tokens.enabled();
    }
}
