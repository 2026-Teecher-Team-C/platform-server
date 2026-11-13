package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.audit.application.AuditLogRecorder;
import com.teecherteamc.platform.audit.domain.AuditAction;
import com.teecherteamc.platform.audit.domain.AuditTargetType;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.verdict.domain.FileFingerprintStore;
import com.trendmicro.tlsh.Tlsh;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportFileFingerprint {
    private final FileFingerprintStore store;
    private final AuditLogRecorder audit;

    public ImportFileFingerprint(FileFingerprintStore store, AuditLogRecorder audit) {
        this.store = store;
        this.audit = audit;
    }

    @Transactional
    public void importFingerprint(String sha256, String tlsh, String provenance, UUID actor, String ip) {
        // 토큰의 옛 역할을 신뢰하지 않고 DB의 현재 활성 ADMIN만 허용한다.
        // 권한 변경도 이 짧은 쓰기 트랜잭션과 직렬화한다.
        if (!store.lockActiveAdmin(actor)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "활성 ADMIN만 기준 지문을 가져올 수 있습니다.");
        }
        // 형식 검사 뒤 공식 파서로 구조를 확인한다. 레거시 70자만 T1 표현으로 명시적으로 변환한다.
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")
                || tlsh == null || !tlsh.matches("(T1)?[0-9A-F]{70}")
                || provenance == null || provenance.isBlank() || provenance.length() > 512) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "SHA256, TLSH 또는 출처 형식이 올바르지 않습니다.");
        }
        String canonical = tlsh.length() == 70 ? "T1" + tlsh : tlsh;
        Tlsh.fromTlshStr(canonical);
        // 해제와 동시에 가져오더라도 활성 항목 확인이 무효화되지 않도록 행 잠금을 유지한다.
        if (!store.lockActiveBaseline(sha256)) {
            throw new ApiException(ErrorCode.CONFLICT, "활성 SHA256 블랙리스트 기준 샘플만 가져올 수 있습니다.");
        }
        if (!store.saveImported(sha256, canonical, provenance, actor)) {
            // 외부 지문으로 서버가 직접 검증한 지문을 덮어쓰면 기준 데이터가 오염될 수 있다.
            throw new ApiException(ErrorCode.CONFLICT, "검증된 업로드 지문은 가져오기로 덮어쓸 수 없습니다.");
        }
        audit.record(actor, AuditAction.IMPORT_FILE_FINGERPRINT, AuditTargetType.FILE_FINGERPRINT,
                sha256, Map.of("tlsh", canonical, "provenance", provenance), ip);
    }
}
