package com.teecherteamc.platform.quarantine.domain;

import java.util.Optional;
import java.util.UUID;

public interface QuarantineFileRepository {

    Optional<QuarantineFile> findById(UUID quarantineId);

    QuarantineFile save(QuarantineFile quarantineFile);
}
