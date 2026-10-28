package com.teecherteamc.platform.fleet.interfaces;

import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.fleet.application.IssueEnrollmentTokenUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트 등록 토큰 발급 API.
 *
 * <p>POST /api/admin/agents/enrollment-tokens (로그인한 관리자, 응답 201).
 * 요청 값 검증과 기본값 적용은 여기서 하고, 발급과 감사 로그는 IssueEnrollmentTokenUseCase가 맡는다.
 */
@RestController
public class EnrollmentTokenController {

    /** expiresInMinutes를 안 보냈을 때 쓰는 값 (1시간). */
    static final long DEFAULT_EXPIRES_IN_MINUTES = 60;

    /** 상한 (24시간). 카드에 숫자가 없어서 정한 값이고, 결정로그에 남긴다. */
    static final long MAX_EXPIRES_IN_MINUTES = 1440;

    private final IssueEnrollmentTokenUseCase issueEnrollmentTokenUseCase;

    public EnrollmentTokenController(IssueEnrollmentTokenUseCase issueEnrollmentTokenUseCase) {
        this.issueEnrollmentTokenUseCase = issueEnrollmentTokenUseCase;
    }

    @PostMapping("/api/admin/agents/enrollment-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueEnrollmentTokenResponse issue(
            // required = false: 본문을 아예 안 보내도 기본값으로 발급한다
            @RequestBody(required = false) IssueEnrollmentTokenRequest request,
            @AuthenticationPrincipal AdminPrincipal principal,
            HttpServletRequest httpRequest) {

        long expiresInMinutes = resolveExpiresInMinutes(request);

        IssueEnrollmentTokenUseCase.Result result = issueEnrollmentTokenUseCase.issue(
                new IssueEnrollmentTokenUseCase.Command(
                        expiresInMinutes, principal.adminId(), ClientIp.resolve(httpRequest)));

        return IssueEnrollmentTokenResponse.from(result);
    }

    /** 본문이 없거나 expiresInMinutes가 없으면 기본값, 1 미만이거나 상한을 넘으면 400. */
    private static long resolveExpiresInMinutes(IssueEnrollmentTokenRequest request) {
        if (request == null || request.expiresInMinutes() == null) {
            return DEFAULT_EXPIRES_IN_MINUTES;
        }
        long value = request.expiresInMinutes();
        if (value < 1 || value > MAX_EXPIRES_IN_MINUTES) {
            throw new ApiException(
                    ErrorCode.INVALID_INPUT,
                    "expiresInMinutes는 1 이상 " + MAX_EXPIRES_IN_MINUTES + " 이하여야 한다");
        }
        return value;
    }
}