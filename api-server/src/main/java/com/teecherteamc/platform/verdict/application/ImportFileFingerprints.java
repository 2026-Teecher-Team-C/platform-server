package com.teecherteamc.platform.verdict.application;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.verdict.domain.FileFingerprintStore;
import com.trendmicro.tlsh.Tlsh;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 기준 지문 일괄 가져오기(블랙리스트 일괄 가져오기가 부른다). 한 건짜리 ImportFileFingerprint와 같은 규칙이지만
 * 감사 로그는 호출자가 묶음당 한 번 남긴다. 호출자의 트랜잭션 안에서만 쓴다.
 */
@Service
public class ImportFileFingerprints {

    private static final Pattern TLSH_FORMAT = Pattern.compile("T1[0-9A-F]{70}");

    private final FileFingerprintStore store;

    public ImportFileFingerprints(FileFingerprintStore store) {
        this.store = store;
    }

    public record BatchResult(int added, int rejected) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BatchResult importBatch(Map<String, String> tlshBySha, String provenance, UUID actor) {
        // 토큰의 옛 역할을 믿지 않고 DB의 현재 활성 ADMIN만 허용한다(한 건짜리 가져오기와 같다).
        if (!store.lockActiveAdmin(actor)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "활성 ADMIN만 기준 지문을 가져올 수 있습니다.");
        }
        Map<String, String> valid = new LinkedHashMap<>();
        int rejected = 0;
        for (Map.Entry<String, String> entry : tlshBySha.entrySet()) {
            if (isParsable(entry.getValue())) {
                valid.put(entry.getKey(), entry.getValue());
            } else {
                rejected++;
            }
        }
        return new BatchResult(store.saveImportedBatch(valid, provenance, actor), rejected);
    }

    // 비교 경로가 공식 파서로 다시 읽으므로, 형식뿐 아니라 구조까지 읽히는 값만 받는다.
    private static boolean isParsable(String tlsh) {
        if (tlsh == null || !TLSH_FORMAT.matcher(tlsh).matches()) {
            return false;
        }
        try {
            Tlsh.fromTlshStr(tlsh);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
