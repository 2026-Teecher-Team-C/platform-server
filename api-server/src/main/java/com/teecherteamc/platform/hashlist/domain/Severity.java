package com.teecherteamc.platform.hashlist.domain;

/**
 * 블랙리스트에 등록된 해시의 위험도.
 * DB의 hash_blacklist.severity 컬럼 값과 1:1로 대응.
 */
public enum Severity {

    /** 낮음 */
    LOW,

    /** 보통 */
    MEDIUM,

    /** 높음. DB 컬럼의 기본값(DEFAULT 'HIGH') */
    HIGH,

    /** 매우 높음 */
    CRITICAL
}