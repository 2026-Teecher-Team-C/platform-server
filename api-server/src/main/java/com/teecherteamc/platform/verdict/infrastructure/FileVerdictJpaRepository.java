package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.FileVerdict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface FileVerdictJpaRepository extends JpaRepository<FileVerdict, String> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE file_verdicts SET is_stale = true WHERE verdict_source = 'ENGINE' AND is_stale = false",
            nativeQuery = true)
    int markEngineVerdictsStale();
}
