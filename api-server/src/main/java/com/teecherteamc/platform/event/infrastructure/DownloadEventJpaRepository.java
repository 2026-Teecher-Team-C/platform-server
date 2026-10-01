package com.teecherteamc.platform.event.infrastructure;

import com.teecherteamc.platform.event.domain.DownloadEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DownloadEventJpaRepository extends JpaRepository<DownloadEvent, UUID> {}
