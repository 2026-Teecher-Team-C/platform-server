package com.teecherteamc.platform.verdict.interfaces;

import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.verdict.application.ImportFileFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FingerprintImportController {
    private final ImportFileFingerprint importer;

    public FingerprintImportController(ImportFileFingerprint importer) {
        this.importer = importer;
    }

    // 인증은 공통 Bearer 필터가 처리한다. 원본 파일 다운로드나 블랙리스트 등록은 하지 않는다.
    @PostMapping("/api/admin/file-fingerprints/import")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void importFingerprint(@Valid @RequestBody Request request,
            @AuthenticationPrincipal AdminPrincipal principal, HttpServletRequest http) {
        importer.importFingerprint(request.sha256(), request.tlsh(), request.provenance(),
                principal.adminId(), ClientIp.resolve(http));
    }

    public record Request(
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sha256,
            @NotBlank @Pattern(regexp = "(T1)?[0-9A-F]{70}") String tlsh,
            @NotBlank @Size(max = 512) String provenance) {
    }
}
