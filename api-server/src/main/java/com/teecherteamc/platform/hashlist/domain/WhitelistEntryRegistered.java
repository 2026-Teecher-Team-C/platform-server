package com.teecherteamc.platform.hashlist.domain;

/**
 * 해시가 화이트리스트에 등록(또는 재활성화)됐다는 도메인 이벤트.
 * verdict 컨텍스트가 받아서 그 해시의 기존 판정을 stale로 표시한다.
 */
public record WhitelistEntryRegistered(String sha256) {
}
