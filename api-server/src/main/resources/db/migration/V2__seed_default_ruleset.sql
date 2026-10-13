-- V2: 기본 룰셋 행 시드.
--
-- analyses.rulesets_version는 NOT NULL + yara_rulesets FK다. 룰셋 관리(업로드/활성화, Sprint 4)가
-- 아직 없어서 yara_rulesets에 행이 하나도 없는데, 탐지 엔진은 지금 AnalyzeResponse.rulesets_version을
-- 채우지 않는다(proto3 기본값 0). 그래서 SubmitFile이 analyses를 기록하려면 참조할 행이 필요하다.
--
-- 룰셋 관리가 들어오면 rulesets_version=1,2,3...으로 실제 버전이 추가된다. 이 0번 행은 지우지 말 것 —
-- "룰셋 관리 이전" 기간에 기록된 analyses 행들이 계속 이 행을 참조한다.
INSERT INTO yara_rulesets (rulesets_version, rule_count, is_active, notes)
VALUES (0, 0, false, '룰셋 관리(Sprint 4) 이전 기본값 — 탐지 엔진이 버전을 보고하지 않던 시기의 analyses가 참조한다');
