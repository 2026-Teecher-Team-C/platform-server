package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.hashlist.domain.HashKey;

/** 감사 로그 target_id 형식: "<해시종류>:<해시값>" (감사 로그 규격 3장). */
final class WhitelistAuditTarget {

    private WhitelistAuditTarget() {
    }

    static String of(HashKey key) {
        return key.getHashType().name() + ":" + key.getHashValue();
    }
}
