package com.teecherteamc.platform.ruleset.application;

/** 같은 버전 번호를 다른 업로드가 먼저 저장했다는 예외. 409로 바꾸는 일은 컨트롤러가 맡는다. */
public class RulesetVersionConflictException extends RuntimeException {

    public RulesetVersionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
