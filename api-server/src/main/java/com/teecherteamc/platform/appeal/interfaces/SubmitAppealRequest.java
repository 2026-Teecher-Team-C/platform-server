package com.teecherteamc.platform.appeal.interfaces;

/** 소명 제출 본문. 길이·공백 검증은 SubmitAppealUseCase가 한다. */
public record SubmitAppealRequest(String reason) {
}
