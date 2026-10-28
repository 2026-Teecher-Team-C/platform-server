-- V5: 룰셋 관리 — 원문 컬럼과 기본 룰 v1 시드.
--
-- source는 업로드된 .yar 원문이다. V2의 v0 행은 원문이 없으므로 NULL을 허용한다.
-- uploaded_by는 업로드한 관리자다. 시드 v1은 사람이 올린 게 아니라 NULL이다.
ALTER TABLE yara_rulesets
    ADD COLUMN source TEXT,
    ADD COLUMN uploaded_by UUID REFERENCES admin_users (admin_id);

-- v1 = 탐지 엔진이 기본 번들로 가진 룰(detection-engine/rules/eicar.yar + teecher_demo.yar).
-- source는 두 파일 내용을 순서대로 그대로 이어 붙인 것이다.
INSERT INTO yara_rulesets (rulesets_version, rule_count, is_active, activated_at, notes, source)
VALUES (1, 2, true, now(), '기본 번들 룰(eicar.yar + teecher_demo.yar)', $yar$// 뼈대용 룰. 실제 룰셋은 yara_rulesets 버전 관리로 들어온다.
rule EICAR_Test_File
{
    meta:
        severity = "HIGH"
        description = "EICAR 안티바이러스 테스트 파일"
    strings:
        $eicar = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!"
    condition:
        $eicar
}
// 데모용 룰. 팀이 만든 가짜 악성 샘플(실행 코드 없는 무해한 파일)을 해시가 아니라 내용으로 잡는 장면을 보여 준다.
// 표식 하나만 언급한 문서·로그는 잡지 않도록 두 문자열이 모두 있어야 맞는다.
rule Teecher_Demo_Fake_Malware
{
    meta:
        severity = "HIGH"
        description = "데모용 가짜 악성 샘플 (표식 + 가짜 C2 주소)"
    strings:
        $marker = "TEECHER-DEMO-MALWARE"
        $c2 = "evil-c2.teecher.invalid"
    condition:
        $marker and $c2
}
$yar$);

INSERT INTO yara_rules (rule_id, rulesets_version, rule_name, severity, enabled)
VALUES (gen_random_uuid(), 1, 'EICAR_Test_File', 'HIGH', true),
       (gen_random_uuid(), 1, 'Teecher_Demo_Fake_Malware', 'HIGH', true);
