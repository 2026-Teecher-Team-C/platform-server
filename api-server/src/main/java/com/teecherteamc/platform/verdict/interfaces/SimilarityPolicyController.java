package com.teecherteamc.platform.verdict.interfaces;

import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.verdict.application.GetSimilarityThresholdUseCase;
import com.teecherteamc.platform.verdict.application.UpdateSimilarityThresholdUseCase;
import com.teecherteamc.platform.verdict.domain.InvalidSimilarityThresholdException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 유사도(TLSH) 임계값 조회·변경 REST 엔드포인트.
 *
 * 로그인(access 토큰)이 필요하고 역할은 SecurityConfig가 정한다. 조회는 ADMIN·ANALYST, 변경은 ADMIN만이다.
 */
@RestController
public class SimilarityPolicyController {

    private final GetSimilarityThresholdUseCase getSimilarityThresholdUseCase;
    private final UpdateSimilarityThresholdUseCase updateSimilarityThresholdUseCase;

    public SimilarityPolicyController(GetSimilarityThresholdUseCase getSimilarityThresholdUseCase,
                                      UpdateSimilarityThresholdUseCase updateSimilarityThresholdUseCase) {
        this.getSimilarityThresholdUseCase = getSimilarityThresholdUseCase;
        this.updateSimilarityThresholdUseCase = updateSimilarityThresholdUseCase;
    }

    /** 지금 적용 중인 임계값과 서버가 허용하는 범위(limits). */
    @GetMapping("/api/admin/similarity-policy")
    public SimilarityThresholdItem get() {
        return SimilarityThresholdItem.from(getSimilarityThresholdUseCase.get());
    }

    /**
     * 임계값을 바꾼다. 다음 검사부터 새 값이 적용되고, 바뀐 값은 같은 모양으로 돌려준다.
     * 범위를 벗어나면 400, 다른 작업이 corpus 락을 오래 쥐고 있어 기다리다 포기하면 409다.
     */
    @PutMapping("/api/admin/similarity-policy")
    public SimilarityThresholdItem update(@AuthenticationPrincipal AdminPrincipal principal,
                                          @Valid @RequestBody UpdateSimilarityThresholdRequest request,
                                          HttpServletRequest httpRequest) {
        try {
            return SimilarityThresholdItem.from(updateSimilarityThresholdUseCase.update(
                    new UpdateSimilarityThresholdUseCase.Command(request.blockDistance(), request.warnDistance(),
                            principal.adminId(), ClientIp.resolve(httpRequest))));
        } catch (InvalidSimilarityThresholdException e) {
            // 범위 위반 → 400. 메시지는 도메인이 쓴 문장 그대로다.
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (PessimisticLockingFailureException e) {
            // 블랙리스트 일괄 적재 같은 작업이 락을 쥐고 있어 대기 시간(5초)을 넘긴 경우 → 409
            throw new ApiException(ErrorCode.CONFLICT, "다른 작업이 진행 중입니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }
}