package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.admin.application.AdminLoginUseCase;
import com.teecherteamc.platform.admin.application.AdminLogoutUseCase;
import com.teecherteamc.platform.admin.application.AdminRefreshUseCase;
import com.teecherteamc.platform.admin.application.AdminTokenIssuer;
import com.teecherteamc.platform.admin.application.CurrentAdminUseCase;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리 콘솔 로그인. Bearer 토큰 방식이다(기능 API 명세 "로그인"). login·refresh만 로그인 없이 부를 수 있고,
 * 나머지는 SecurityConfig가 access 토큰을 요구한다.
 */
@RestController
public class AdminAuthController {

    // 없는 계정·틀린 비밀번호·비활성 계정을 구분하지 않는다. 구분하면 계정 존재 여부가 드러난다.
    private static final String LOGIN_FAILED_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다.";
    private static final String REFRESH_FAILED_MESSAGE = "refresh 토큰이 유효하지 않습니다. 다시 로그인하세요.";

    private final AdminLoginUseCase adminLoginUseCase;
    private final AdminRefreshUseCase adminRefreshUseCase;
    private final AdminLogoutUseCase adminLogoutUseCase;
    private final CurrentAdminUseCase currentAdminUseCase;
    private final AdminTokenIssuer adminTokenIssuer;

    public AdminAuthController(AdminLoginUseCase adminLoginUseCase, AdminRefreshUseCase adminRefreshUseCase,
                               AdminLogoutUseCase adminLogoutUseCase, CurrentAdminUseCase currentAdminUseCase,
                               AdminTokenIssuer adminTokenIssuer) {
        this.adminLoginUseCase = adminLoginUseCase;
        this.adminRefreshUseCase = adminRefreshUseCase;
        this.adminLogoutUseCase = adminLogoutUseCase;
        this.currentAdminUseCase = currentAdminUseCase;
        this.adminTokenIssuer = adminTokenIssuer;
    }

    @PostMapping("/api/admin/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return adminLoginUseCase.login(body.username(), body.password(), ClientIp.resolve(request))
                .map(adminTokenIssuer::issue)
                .map(TokenResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE));
    }

    /** 쓴 refresh 토큰은 폐기되고 새 토큰 한 쌍을 준다. */
    @PostMapping("/api/admin/auth/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest body) {
        return adminRefreshUseCase.refresh(body.refreshToken())
                .map(TokenResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, REFRESH_FAILED_MESSAGE));
    }

    @PostMapping("/api/admin/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AdminPrincipal principal,
                       @RequestBody(required = false) LogoutRequest body, HttpServletRequest request) {
        adminLogoutUseCase.logout(principal.adminId(), body == null ? null : body.refreshToken(),
                ClientIp.resolve(request));
    }

    /** access 토큰이 유효해도 그사이 계정이 비활성화됐으면 401을 준다. */
    @GetMapping("/api/admin/auth/me")
    public CurrentAdminResponse me(@AuthenticationPrincipal AdminPrincipal principal) {
        return currentAdminUseCase.findActive(principal.adminId())
                .map(CurrentAdminResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
