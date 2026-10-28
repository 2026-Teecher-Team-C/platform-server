package com.teecherteamc.platform.quarantine.infrastructure;

import com.teecherteamc.platform.quarantine.domain.QuarantineFile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface QuarantineFileJpaRepository extends JpaRepository<QuarantineFile, UUID> {}
