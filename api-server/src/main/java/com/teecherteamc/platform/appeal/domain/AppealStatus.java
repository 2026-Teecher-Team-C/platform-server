package com.teecherteamc.platform.appeal.domain;

/** appeals.status 값. PENDING일 때만 decided_at이 비어 있다(ck_appeals_decided). */
public enum AppealStatus {
    PENDING,
    APPROVED,
    REJECTED
}
