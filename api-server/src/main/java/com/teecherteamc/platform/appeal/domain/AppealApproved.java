package com.teecherteamc.platform.appeal.domain;

import java.util.UUID;

/**
 * 소명이 승인됐다는 도메인 이벤트. hashlist 컨텍스트가 같은 트랜잭션 안에서 받아 그 해시를 화이트리스트에
 * 등록(또는 재활성화)하고 활성 블랙리스트를 해제한다 — appeal은 hashlist의 애그리거트를 직접 건드리지 않는다.
 * reason은 화이트리스트 등록 사유(승인 의견, 없으면 "소명 승인")다.
 */
public record AppealApproved(UUID appealId, String sha256, String reason, UUID adminId, String ipAddress) {
}
