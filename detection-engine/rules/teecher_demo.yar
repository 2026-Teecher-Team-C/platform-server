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
