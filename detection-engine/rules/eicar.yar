// 뼈대용 룰. 실제 룰셋은 yara_rulesets 버전 관리로 들어온다.
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
