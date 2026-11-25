package com.teecherteamc.platform.appeal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AppealLinksTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final String SHA = "b".repeat(64);

    private AppealLinks links(String secret, String baseUrl) {
        return new AppealLinks(secret, baseUrl, Duration.ofDays(7),
                Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC), JsonMapper.builder().build());
    }

    @Test
    void url_uses_base_url_without_trailing_slash() {
        String url = links(SECRET, "http://c/").urlFor(EVENT, SHA, AGENT, VerdictEvidence.blacklist("x"));

        assertThat(url).startsWith("http://c/appeal/").doesNotContain("//appeal");
    }

    @Test
    void blank_base_url_returns_empty() {
        assertThat(links(SECRET, " ").urlFor(EVENT, SHA, AGENT, VerdictEvidence.blacklist("x"))).isEmpty();
    }

    @Test
    void missing_secret_returns_empty() {
        assertThat(links("", "http://c").urlFor(EVENT, SHA, AGENT, VerdictEvidence.blacklist("x"))).isEmpty();
    }

    @Test
    void yara_threat_is_first_rule_name() {
        AppealLinks links = links(SECRET, "http://c");
        VerdictEvidence yara = VerdictEvidence.yara(List.of(
                new VerdictEvidence.Rule("First", "high"), new VerdictEvidence.Rule("Second", "low")));

        String url = links.urlFor(EVENT, SHA, AGENT, yara);
        String token = url.substring("http://c/appeal/".length());

        assertThat(links.tokens().verify(token).get().threat()).isEqualTo("First");
    }
}
