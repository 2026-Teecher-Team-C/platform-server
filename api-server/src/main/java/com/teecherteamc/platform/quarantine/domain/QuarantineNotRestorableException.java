package com.teecherteamc.platform.quarantine.domain;

import java.util.UUID;

/** QUARANTINED 상태가 아닌 격리 파일을 복원하려 할 때(이미 RESTORED 등). 컨트롤러가 409로 바꾼다. */
public class QuarantineNotRestorableException extends RuntimeException {

    public QuarantineNotRestorableException(UUID quarantineId, String currentStatus) {
        super("quarantine file is not QUARANTINED (current status: " + currentStatus + "): " + quarantineId);
    }
}
