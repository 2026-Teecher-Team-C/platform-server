package com.teecherteamc.platform.ruleset.application;

/** 올린 룰셋 파일을 받아들일 수 없다는 예외. 400/422로 바꾸는 일은 컨트롤러가 맡는다. */
public class InvalidRulesetException extends RuntimeException {

    /** BAD_INPUT: 파일 자체가 잘못됨(400). UNPROCESSABLE: 파일은 왔지만 룰셋으로 쓸 수 없음(422). */
    public enum Kind { BAD_INPUT, UNPROCESSABLE }

    private final Kind kind;

    public InvalidRulesetException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
