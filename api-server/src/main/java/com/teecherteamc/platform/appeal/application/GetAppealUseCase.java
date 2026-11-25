package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.appeal.domain.AppealClaims;
import com.teecherteamc.platform.appeal.domain.AppealRepository;
import com.teecherteamc.platform.appeal.domain.FindBlockedDownloadPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 소명 링크로 들어온 직원에게 차단 정보와 (있으면) 제출한 소명을 보여 준다. */
@Service
public class GetAppealUseCase {

    private final AppealLinks appealLinks;
    private final AppealRepository appealRepository;
    private final FindBlockedDownloadPort findBlockedDownload;

    public GetAppealUseCase(AppealLinks appealLinks, AppealRepository appealRepository,
                            FindBlockedDownloadPort findBlockedDownload) {
        this.appealLinks = appealLinks;
        this.appealRepository = appealRepository;
        this.findBlockedDownload = findBlockedDownload;
    }

    @Transactional(readOnly = true)
    public AppealView get(String token) {
        AppealClaims claims = appealLinks.tokens().verify(token).orElseThrow(AppealNotFoundException::new);
        return AppealView.of(
                claims,
                findBlockedDownload.find(claims.eventId(), claims.agentId()).orElse(null),
                appealRepository.findByEventId(claims.eventId()).orElse(null));
    }
}
