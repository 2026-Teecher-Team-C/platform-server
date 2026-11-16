package com.teecherteamc.platform.lookup.application;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CheckHash 입력. sha256은 정규화·검증된 값이다. tlsh·eventId는 구버전 에이전트면 null이다.
 *
 * @param tlsh 에이전트가 계산해 보낸 미검증 TLSH. {@link #claimedTlsh(String)}를 거친 값만 넣는다.
 * @param agentId 인증된 에이전트. eventId가 있으면 그 이벤트의 소유자로 기록된다.
 */
public record CheckHashQuery(String sha256, long fileSize, String tlsh, UUID eventId, UUID agentId) {

    /**
     * 에이전트 TLSH의 유일한 허용 형식. 서버 계산기(OfficialTlshCalculator)와 file_fingerprints 제약
     * (V8)이 쓰는 것과 같은 "T1" + 대문자 hex 70자다. Tlsh.fromTlshStr는 T1 없는 구형·소문자도 받으므로
     * 여기서 먼저 걸러야 같은 파일이 표기만 달리해 다른 경로를 타지 않는다.
     */
    private static final Pattern TLSH_FORMAT = Pattern.compile("^T1[0-9A-F]{70}$");

    /** 형식이 다르면 TLSH가 없는 것으로 본다(오류 대신 기존 흐름: 업로드 후 서버가 직접 계산). */
    public static String claimedTlsh(String raw) {
        return raw != null && TLSH_FORMAT.matcher(raw).matches() ? raw : null;
    }
}
