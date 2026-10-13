package com.teecherteamc.platform.fleet.infrastructure;

import com.teecherteamc.platform.fleet.domain.Device;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeviceJpaRepository extends JpaRepository<Device, UUID> {}
