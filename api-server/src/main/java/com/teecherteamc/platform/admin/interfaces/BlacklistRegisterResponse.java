package com.teecherteamc.platform.admin.interfaces;

import com.teecherteamc.platform.hashlist.application.AddBlacklistEntryUseCase;
import com.teecherteamc.platform.hashlist.interfaces.BlacklistItem;
import java.util.List;

/** POST /api/admin/blacklist 응답. 등록된 항목과 함께, 이 해시를 이미 받은 기기 목록을 실어 보낸다(IMP-10). */
public record BlacklistRegisterResponse(BlacklistItem entry, List<AffectedDeviceItem> affectedDevices) {

    public static BlacklistRegisterResponse from(AddBlacklistEntryUseCase.Result result, String username) {
        return new BlacklistRegisterResponse(
                BlacklistItem.from(result.entry(), username),
                result.affectedDevices().stream().map(AffectedDeviceItem::from).toList());
    }
}
