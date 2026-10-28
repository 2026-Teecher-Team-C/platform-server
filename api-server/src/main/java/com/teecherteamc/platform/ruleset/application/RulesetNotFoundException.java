package com.teecherteamc.platform.ruleset.application;

/** 요청한 룰셋 버전(또는 그 안의 룰)이 없다는 예외. 404로 바꾸는 일은 컨트롤러가 맡는다. */
public class RulesetNotFoundException extends RuntimeException {

    public RulesetNotFoundException(String message) {
        super(message);
    }

    public static RulesetNotFoundException ofVersion(int version) {
        return new RulesetNotFoundException("룰셋 v" + version + "이(가) 없습니다.");
    }
}
