package com.teecherteamc.platform.appeal.application;

import com.teecherteamc.platform.appeal.domain.ListAppealsPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** 관리자 소명 목록. status·page·size 범위 검증은 interfaces 계층이 한다. */
@Service
public class ListAppealsUseCase {

    private final ListAppealsPort listAppealsPort;

    public ListAppealsUseCase(ListAppealsPort listAppealsPort) {
        this.listAppealsPort = listAppealsPort;
    }

    public Page<AdminAppealListItem> list(String status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        var items = listAppealsPort.list(status, pageRequest.getOffset(), size).stream()
                .map(AdminAppealListItem::from).toList();
        return new PageImpl<>(items, pageRequest, listAppealsPort.count(status));
    }
}
