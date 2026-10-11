package com.teecherteamc.platform.verdict.domain;

import java.util.Optional;

public interface FileVerdictRepository {

    Optional<FileVerdict> findBySha256(String sha256);

    FileVerdict save(FileVerdict fileVerdict);
}
