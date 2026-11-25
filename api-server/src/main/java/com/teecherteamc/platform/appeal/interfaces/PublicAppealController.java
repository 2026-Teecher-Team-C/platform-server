package com.teecherteamc.platform.appeal.interfaces;

import com.teecherteamc.platform.appeal.application.AppealAlreadySubmittedException;
import com.teecherteamc.platform.appeal.application.AppealNotFoundException;
import com.teecherteamc.platform.appeal.application.AppealView;
import com.teecherteamc.platform.appeal.application.GetAppealUseCase;
import com.teecherteamc.platform.appeal.application.InvalidAppealReasonException;
import com.teecherteamc.platform.appeal.application.SubmitAppealUseCase;
import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 직원용 소명 REST 엔드포인트. 로그인 없이 서명된 링크 토큰만으로 접근한다.
 * SecurityConfig가 이 경로만 permitAll로 연다. 토큰이 틀리거나 만료되면 모두 같은 404다.
 */
@RestController
public class PublicAppealController {

    private final GetAppealUseCase getAppealUseCase;
    private final SubmitAppealUseCase submitAppealUseCase;

    public PublicAppealController(GetAppealUseCase getAppealUseCase, SubmitAppealUseCase submitAppealUseCase) {
        this.getAppealUseCase = getAppealUseCase;
        this.submitAppealUseCase = submitAppealUseCase;
    }

    @GetMapping("/api/public/appeals/{token}")
    public AppealView get(@PathVariable String token) {
        try {
            return getAppealUseCase.get(token);
        } catch (AppealNotFoundException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/api/public/appeals/{token}")
    @ResponseStatus(HttpStatus.CREATED)
    public AppealView submit(@PathVariable String token, @RequestBody SubmitAppealRequest request,
                             HttpServletRequest httpRequest) {
        try {
            return submitAppealUseCase.submit(token, request.reason(), ClientIp.resolve(httpRequest));
        } catch (AppealNotFoundException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, e.getMessage());
        } catch (InvalidAppealReasonException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (AppealAlreadySubmittedException e) {
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
    }
}
