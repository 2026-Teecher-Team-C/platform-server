package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.admin.application.AdminLoginUseCase;
import com.teecherteamc.platform.admin.application.AdminLogoutUseCase;
import com.teecherteamc.platform.admin.application.CurrentAdminUseCase;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리 콘솔 로그인. 서버 세션 쿠키 방식이다(2026-10-18 팀 결정). login만 로그인 없이 부를 수 있고,
 * 나머지는 SecurityConfig가 로그인을 요구한다.
 */
@RestController
public class AdminAuthController {

    // 없는 계정·틀린 비밀번호·비활성 계정을 구분하지 않는다. 구분하면 계정 존재 여부가 드러난다.
    private static final String LOGIN_FAILED_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다.";

    private final AdminLoginUseCase adminLoginUseCase;
    private final AdminLogoutUseCase adminLogoutUseCase;
    private final CurrentAdminUseCase currentAdminUseCase;
    private final AdminSession adminSession;

    public AdminAuthController(AdminLoginUseCase adminLoginUseCase, AdminLogoutUseCase adminLogoutUseCase,
                               CurrentAdminUseCase currentAdminUseCase, AdminSession adminSession) {
        this.adminLoginUseCase = adminLoginUseCase;
        this.adminLogoutUseCase = adminLogoutUseCase;
        this.currentAdminUseCase = currentAdminUseCase;
        this.adminSession = adminSession;
    }

    @PostMapping("/api/admin/auth/login")
    public CurrentAdminResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                                      HttpServletResponse response) {
        return adminLoginUseCase.login(body.username(), body.password(), ClientIp.resolve(request))
                .map(admin -> {
                    adminSession.establish(admin, request, response);
                    return CurrentAdminResponse.from(admin);
                })
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE));
    }

    @PostMapping("/api/admin/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AdminPrincipal principal, HttpServletRequest request) {
        adminLogoutUseCase.logout(principal.adminId(), ClientIp.resolve(request));
        adminSession.terminate(request);
    }

    /** 로그인 뒤에 계정이 비활성화됐으면 세션을 끊고 401을 준다. */
    @GetMapping("/api/admin/auth/me")
    public CurrentAdminResponse me(@AuthenticationPrincipal AdminPrincipal principal, HttpServletRequest request) {
        return currentAdminUseCase.findActive(principal.adminId())
                .map(CurrentAdminResponse::from)
                .orElseThrow(() -> {
                    adminSession.terminate(request);
                    return new ApiException(ErrorCode.UNAUTHORIZED);
                });
    }
}
