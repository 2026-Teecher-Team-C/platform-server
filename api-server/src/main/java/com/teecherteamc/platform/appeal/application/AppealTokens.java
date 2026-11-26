package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.appeal.domain.AppealClaims;
import com.teecherteamc.platform.verdict.domain.VerdictEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 소명 링크 토큰. {@code base64url(payload) + "." + base64url(HMAC-SHA256(secret, payload))}, 패딩 없음.
 * payload는 {@code {v:1, e, s, a, k, t, x}}. 비밀키가 32바이트 미만이면 꺼진다(기동은 막지 않는다).
 */
public class AppealTokens {

    private static final int MIN_SECRET_BYTES = 32;
    private static final int VERSION = 1;
    private static final String HMAC = "HmacSHA256";

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;
    private final JsonMapper jsonMapper;

    public AppealTokens(String secret, Duration ttl, Clock clock, JsonMapper jsonMapper) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
    }

    public boolean enabled() {
        return secret.length >= MIN_SECRET_BYTES;
    }

    public String issue(UUID eventId, String sha256, UUID agentId, VerdictEvidence evidence) {
        if (!enabled()) {
            throw new IllegalStateException("소명 토큰 비밀키가 없거나 32바이트 미만입니다");
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("v", VERSION);
        payload.put("e", eventId.toString());
        payload.put("s", sha256);
        payload.put("a", agentId.toString());
        payload.put("k", evidence.kind().name());
        payload.put("t", threatOf(evidence));
        payload.put("x", clock.instant().plus(ttl).getEpochSecond());
        byte[] json = jsonMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(json) + "." + encoder.encodeToString(sign(json));
    }

    public Optional<AppealClaims> verify(String token) {
        if (!enabled() || token == null) {
            return Optional.empty();
        }
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) {
                return Optional.empty();
            }
            byte[] json = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sign(json), signature)) {
                return Optional.empty();
            }
            JsonNode node = jsonMapper.readTree(new String(json, StandardCharsets.UTF_8));
            if (node.path("v").asInt(-1) != VERSION) {
                return Optional.empty();
            }
            Instant expiresAt = Instant.ofEpochSecond(node.path("x").asLong());
            if (!clock.instant().isBefore(expiresAt)) {
                return Optional.empty();
            }
            return Optional.of(new AppealClaims(
                    UUID.fromString(node.path("e").asString()),
                    node.path("s").asString(),
                    UUID.fromString(node.path("a").asString()),
                    VerdictEvidence.Kind.valueOf(node.path("k").asString()),
                    node.path("t").asString(""),
                    expiresAt));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String threatOf(VerdictEvidence evidence) {
        return switch (evidence.kind()) {
            case BLACKLIST, SIMILARITY -> evidence.family();
            case YARA -> evidence.rules().isEmpty() ? "" : evidence.rules().get(0).name();
            default -> "";
        };
    }

    private byte[] sign(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
