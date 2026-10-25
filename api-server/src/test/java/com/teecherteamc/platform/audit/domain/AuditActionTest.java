package com.teecherteamc.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AuditActionTest {

    @Test
    void 기능_API_명세의_이번_범위_action을_모두_기록할_수_있다() {
        // Notion 기능 API 명세 "감사 로그 조회" 비즈니스 로직 4번의 목록
        assertThat(Arrays.stream(AuditAction.values()).map(Enum::name)).contains(
                "LOGIN", "ISSUE_ENROLLMENT_TOKEN",
                "ADD_WHITELIST", "UPDATE_WHITELIST", "REMOVE_WHITELIST",
                "ADD_BLACKLIST", "UPDATE_BLACKLIST", "REMOVE_BLACKLIST",
                "RESTORE_QUARANTINE", "ADD_BYPASS_DOMAIN", "REMOVE_BYPASS_DOMAIN");
    }

    @Test
    void 등록_토큰_발급의_대상_종류가_있다() {
        assertThat(AuditTargetType.valueOf("ENROLLMENT_TOKEN")).isNotNull();
    }
}
