-- 활성 룰셋 버전에서 꺼진(enabled = false) 룰 이름. 한 줄에 하나.
SELECT r.rule_name
FROM yara_rules r JOIN yara_rulesets s ON s.rulesets_version = r.rulesets_version
WHERE s.is_active AND NOT r.enabled
ORDER BY r.rule_name;
