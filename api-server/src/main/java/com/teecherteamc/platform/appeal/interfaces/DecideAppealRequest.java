package com.teecherteamc.platform.appeal.interfaces;

/** 소명 승인·거절 본문. 승인은 note가 선택, 거절은 필수다. */
public record DecideAppealRequest(String note) {
}
