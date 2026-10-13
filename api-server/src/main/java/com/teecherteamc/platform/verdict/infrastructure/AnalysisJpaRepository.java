package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.Analysis;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AnalysisJpaRepository extends JpaRepository<Analysis, UUID> {}
