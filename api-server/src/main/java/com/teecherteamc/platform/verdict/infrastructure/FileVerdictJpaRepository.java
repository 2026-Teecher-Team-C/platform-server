package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.FileVerdict;
import org.springframework.data.jpa.repository.JpaRepository;

interface FileVerdictJpaRepository extends JpaRepository<FileVerdict, String> {}
