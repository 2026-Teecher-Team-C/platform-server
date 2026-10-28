package com.teecherteamc.platform;

import org.springframework.jdbc.core.JdbcTemplate;

/** 룰셋 활성화·룰 토글 테스트 공용 정리. 공유 DB를 시드 상태(v1 활성, 룰 모두 켜짐)로 되돌린다. */
final class RulesetTestSupport {

    private RulesetTestSupport() {
    }

    static void restoreSeed(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM audit_logs WHERE action IN "
                + "('UPLOAD_RULESET', 'ACTIVATE_RULESET', 'ENABLE_YARA_RULE', 'DISABLE_YARA_RULE')");
        jdbc.update("DELETE FROM file_verdicts WHERE rulesets_version > 1");
        jdbc.update("UPDATE yara_rulesets SET is_active = false");
        jdbc.update("DELETE FROM yara_rulesets WHERE rulesets_version > 1");
        jdbc.update("UPDATE yara_rulesets SET is_active = true WHERE rulesets_version = 1");
        jdbc.update("UPDATE yara_rules SET enabled = true WHERE rulesets_version = 1");
    }
}
