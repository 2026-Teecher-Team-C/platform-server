package com.teecherteamc.platform;

import com.teecherteamc.platform.verdict.domain.QuarantineUploadPort;
import com.teecherteamc.platform.verdict.domain.QuarantineUploadResult;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 테스트 전용 가짜 S3 업로드. 실제 S3/MinIO 없이 매번 새 bucket/key만 돌려준다 — 업로드 내용
 * 자체는 아무 테스트도 검증하지 않고, SubmitFile이 pending_quarantine_uploads에 뭘 적어뒀는지만
 * 확인하면 충분하다. @Primary로 실제 S3QuarantineUploadPortImpl 빈을 덮어쓴다(그 빈도 테스트
 * 컨텍스트에 같이 뜨지만, endpoint-override가 비어있어 @PostConstruct가 아무 것도 안 하므로
 * 네트워크 접근 없이 조용히 공존한다).
 */
@Component
@Primary
class FakeQuarantineUploadPort implements QuarantineUploadPort {

    @Override
    public QuarantineUploadResult upload(Path file) {
        return new QuarantineUploadResult("fake-quarantine-bucket", UUID.randomUUID().toString());
    }
}
