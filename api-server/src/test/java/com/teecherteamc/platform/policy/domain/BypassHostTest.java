package com.teecherteamc.platform.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BypassHostTest {

    @Test
    void 공백을_자르고_소문자로_바꾸고_끝의_점을_뗀다() {
        assertThat(BypassHost.of("  Update.Vendor.COM. ").value()).isEqualTo("update.vendor.com");
    }

    @Test
    void 대소문자만_다른_host는_같은_값이다() {
        assertThat(BypassHost.of("Example.com")).isEqualTo(BypassHost.of("example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "a-b.example.co.kr", "1password.com", "xn--abc.example.com"})
    void 올바른_host는_통과한다(String raw) {
        assertThat(BypassHost.of(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "."})
    void 비어_있으면_거부한다(String raw) {
        assertThatThrownBy(() -> BypassHost.of(raw)).isInstanceOf(InvalidBypassDomainException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*.google.com", "a.*.com"})
    void 와일드카드는_거부한다(String raw) {
        assertThatThrownBy(() -> BypassHost.of(raw))
                .isInstanceOf(InvalidBypassDomainException.class)
                .hasMessageContaining("와일드카드");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com", "example.com/path", "example.com:443", "user@example.com",
            "example.com?x=1", "example.com#top", "exa mple.com"})
    void 스킴_경로_포트_계정_공백이_들어_있으면_거부한다(String raw) {
        assertThatThrownBy(() -> BypassHost.of(raw)).isInstanceOf(InvalidBypassDomainException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "com", "a..com", ".example.com", "-a.com", "a-.com", "a_b.com", "한글.com"})
    void 도메인_형식이_아니면_거부한다(String raw) {
        assertThatThrownBy(() -> BypassHost.of(raw)).isInstanceOf(InvalidBypassDomainException.class);
    }

    @Test
    void IP_주소는_거부한다() {
        assertThatThrownBy(() -> BypassHost.of("1.2.3.4"))
                .isInstanceOf(InvalidBypassDomainException.class)
                .hasMessageContaining("IP");
    }

    @Test
    void 라벨이_63자를_넘으면_거부한다() {
        assertThat(BypassHost.of("a".repeat(63) + ".com")).isNotNull();
        assertThatThrownBy(() -> BypassHost.of("a".repeat(64) + ".com"))
                .isInstanceOf(InvalidBypassDomainException.class);
    }

    @Test
    void 전체가_253자를_넘으면_거부한다() {
        // 60자 라벨 5개 + 점 4개 + ".com" = 308자. 각 라벨은 63자 이하라서 전체 길이 규칙만 걸린다.
        String label = "a".repeat(60);
        String tooLong = String.join(".", label, label, label, label, label) + ".com";
        assertThatThrownBy(() -> BypassHost.of(tooLong))
                .isInstanceOf(InvalidBypassDomainException.class)
                .hasMessageContaining("253");
    }
}
