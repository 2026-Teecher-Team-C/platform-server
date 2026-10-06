package com.teecherteamc.platform.hashlist.domain;

/**
 * 블랙리스트 항목의 등록 방식.
 * DB의 hash_blacklist.source 컬럼 값과 1:1로 대응.
 */
public enum EntrySource {

    /** 운영자가 콘솔에서 직접 등록. (DB 컬럼의 기본값) */
    MANUAL,

    /**
     * 일괄 등록했다.
     * 외부 위협 정보(TI) 피드와 자동으로 연동하는 기능은 아님.
     */
    IMPORT
}