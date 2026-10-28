package com.teecherteamc.platform.verdict.domain;

import java.util.Optional;

public interface FileVerdictRepository {

    Optional<FileVerdict> findBySha256(String sha256);

    FileVerdict save(FileVerdict fileVerdict);

    /** 엔진이 내린 판정 중 아직 stale이 아닌 것을 모두 stale로 바꾼다. 바뀐 행 수를 준다. */
    int markEngineVerdictsStale();
}
