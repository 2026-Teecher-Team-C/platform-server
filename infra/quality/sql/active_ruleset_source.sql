-- 활성 YARA 룰셋 원문. 활성 룰셋은 항상 1개다(uq_yara_rulesets_active). 끝에 줄바꿈 하나가 붙는다.
SELECT source FROM yara_rulesets WHERE is_active;
