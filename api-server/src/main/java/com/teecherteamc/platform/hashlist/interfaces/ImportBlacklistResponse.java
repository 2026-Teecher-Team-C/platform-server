package com.teecherteamc.platform.hashlist.interfaces;

import com.teecherteamc.platform.hashlist.application.ImportBlacklistBatchUseCase;

/** 일괄 가져오기 결과 건수. 스크립트가 묶음마다 합산한다. */
public record ImportBlacklistResponse(int received, int added, int skippedActive, int skippedInactive,
        int skippedWhitelisted, int invalid, int fingerprintsAdded, int fingerprintsSkipped) {

    static ImportBlacklistResponse from(ImportBlacklistBatchUseCase.Result r) {
        return new ImportBlacklistResponse(r.received(), r.added(), r.skippedActive(), r.skippedInactive(),
                r.skippedWhitelisted(), r.invalid(), r.fingerprintsAdded(), r.fingerprintsSkipped());
    }
}
