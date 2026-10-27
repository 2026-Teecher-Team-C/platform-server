package com.teecherteamc.platform.hashlist.domain;

import java.time.Instant;
import java.util.UUID;

/** 목록 화면용 화이트리스트 한 줄. admin_users와 조인해 등록자 username까지 담는다. */
public record WhitelistRow(
        String hashType,
        String hashValue,
        String reason,
        UUID originQuarantineId,
        UUID addedById,
        String addedByUsername,
        boolean active,
        Instant expiresAt,
        Instant createdAt) {
}
