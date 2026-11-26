package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.appeal.domain.Appeal;
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
        Appeal appeal = appealRepository.findByEventId(claims.eventId()).orElse(null);
        // 다른 사람의 소명이면 appeal: null로 비우지 않고 404로 숨긴다(존재 자체를 알리지 않는다)
        if (appeal != null && !appeal.isOwnedBy(claims.agentId(), claims.sha256())) {
            throw new AppealNotFoundException();
        }
        return AppealView.of(
                claims,
                findBlockedDownload.find(claims.eventId(), claims.agentId()).orElse(null),
                appeal);
    }
}
