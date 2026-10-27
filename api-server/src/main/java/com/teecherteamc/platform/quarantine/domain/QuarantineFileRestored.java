package com.teecherteamc.platform.quarantine.domain;

import java.util.UUID;

/**
 * 격리 파일이 오탐 복원됐다는 도메인 이벤트. hashlist 컨텍스트가 받아서 화이트리스트 등록(또는
 * 재활성화)과 충돌하는 블랙리스트 해제를 처리한다 — quarantine은 hashlist의 애그리거트를 직접 건드리지 않는다.
 */
public record QuarantineFileRestored(UUID quarantineId, String sha256, String reason, UUID adminId, String ipAddress) {
}
