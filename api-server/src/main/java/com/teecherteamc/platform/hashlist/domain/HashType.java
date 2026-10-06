package com.teecherteamc.platform.hashlist.domain;

/**
 * 해시의 종류. DB의 hash_type 컬럼 값과 1:1로 대응한다.
 * (이름이 DB 값과 한 글자라도 다르면 JPA가 값을 읽지 못한다)
 */
public enum HashType {

    /** 파일 내용의 SHA-256 지문. 현재 시스템이 실제로 쓰는 유일한 종류다. */
    SHA256,

    /** 유사도 해시. 블랙리스트용으로 자리만 예약해 둔 값이라, 지금은 등록하지 않는다. */
    TLSH
}