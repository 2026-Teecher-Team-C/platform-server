package com.teecherteamc.platform.admin.application;

import com.teecherteamc.platform.admin.domain.RefreshTokenStore;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * refresh 토큰으로 새 토큰 한 쌍을 발급한다. 쓴 refresh 토큰은 바로 폐기한다(회전) — 새어 나간 토큰이 계속
 * 쓰이지 않게 한다. 그사이 비활성화된 계정이면 발급하지 않는다.
 */
@Service
public class AdminRefreshUseCase {

    private final RefreshTokenStore refreshTokenStore;
    private final CurrentAdminUseCase currentAdminUseCase;
    private final AdminTokenIssuer adminTokenIssuer;

    public AdminRefreshUseCase(RefreshTokenStore refreshTokenStore, CurrentAdminUseCase currentAdminUseCase,
                               AdminTokenIssuer adminTokenIssuer) {
        this.refreshTokenStore = refreshTokenStore;
        this.currentAdminUseCase = currentAdminUseCase;
        this.adminTokenIssuer = adminTokenIssuer;
    }

    public Optional<AdminTokens> refresh(String refreshToken) {
        return refreshTokenStore.consume(refreshToken)
                .flatMap(currentAdminUseCase::findActive)
                .map(adminTokenIssuer::issue);
    }
}
