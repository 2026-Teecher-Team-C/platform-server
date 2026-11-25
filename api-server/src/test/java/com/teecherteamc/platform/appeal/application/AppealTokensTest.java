package com.teecherteamc.platform.appeal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.appeal.domain.AppealClaims;
import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AppealTokensTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-11-26T00:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final String SHA = "a".repeat(64);

    private final JsonMapper mapper = JsonMapper.builder().build();

    private AppealTokens tokens(String secret, Instant now) {
        return new AppealTokens(secret, TTL, Clock.fixed(now, ZoneOffset.UTC), mapper);
    }

    private String issue() {
        return tokens(SECRET, NOW).issue(EVENT, SHA, AGENT, VerdictEvidence.blacklist("MalwareBazaar: Emotet"));
    }

    @Test
    void issue_then_verify_round_trips() {
        Optional<AppealClaims> claims = tokens(SECRET, NOW).verify(issue());

        assertThat(claims).isPresent();
        assertThat(claims.get().eventId()).isEqualTo(EVENT);
        assertThat(claims.get().sha256()).isEqualTo(SHA);
        assertThat(claims.get().agentId()).isEqualTo(AGENT);
        assertThat(claims.get().kind()).isEqualTo(VerdictEvidence.Kind.BLACKLIST);
        assertThat(claims.get().threat()).isEqualTo("Emotet");
        assertThat(claims.get().expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void tampered_payload_is_rejected() {
        String[] parts = issue().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
                .replace("BLACKLIST", "YARA");
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[1];

        assertThat(tokens(SECRET, NOW).verify(forged)).isEmpty();
    }

    @Test
    void tampered_signature_is_rejected() {
        String token = issue();
        char last = token.charAt(token.length() - 1);
        String forged = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThat(tokens(SECRET, NOW).verify(forged)).isEmpty();
    }

    @Test
    void token_signed_with_other_secret_is_rejected() {
        String token = tokens("fedcba9876543210fedcba9876543210", NOW)
                .issue(EVENT, SHA, AGENT, VerdictEvidence.blacklist("x"));

        assertThat(tokens(SECRET, NOW).verify(token)).isEmpty();
    }

    @Test
    void expired_token_is_rejected() {
        String token = issue();

        assertThat(tokens(SECRET, NOW.plus(TTL).plusSeconds(1)).verify(token)).isEmpty();
        assertThat(tokens(SECRET, NOW.plus(TTL).minusSeconds(1)).verify(token)).isPresent();
    }

    @Test
    void garbage_is_rejected() {
        AppealTokens tokens = tokens(SECRET, NOW);

        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify("a.b")).isEmpty();
        assertThat(tokens.verify("...")).isEmpty();
        assertThat(tokens.verify(null)).isEmpty();
    }

    @Test
    void short_secret_is_disabled() {
        AppealTokens shortSecret = tokens("a".repeat(31), NOW);

        assertThat(shortSecret.enabled()).isFalse();
        assertThat(shortSecret.verify(issue())).isEmpty();
        assertThat(tokens("a".repeat(32), NOW).enabled()).isTrue();
    }

    @Test
    void threat_name_follows_kind() {
        AppealTokens tokens = tokens(SECRET, NOW);
        VerdictEvidence yara = VerdictEvidence.yara(List.of(new VerdictEvidence.Rule("Rule_A", "high")));

        assertThat(tokens.verify(tokens.issue(EVENT, SHA, AGENT, yara)).get().threat()).isEqualTo("Rule_A");
        assertThat(tokens.verify(tokens.issue(EVENT, SHA, AGENT, VerdictEvidence.yara(List.of()))).get().threat())
                .isEmpty();
        assertThat(tokens.verify(tokens.issue(EVENT, SHA, AGENT, VerdictEvidence.cache("BLOCK"))).get().threat())
                .isEmpty();
    }
}
