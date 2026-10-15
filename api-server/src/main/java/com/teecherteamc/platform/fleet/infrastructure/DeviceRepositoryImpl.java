package com.teecherteamc.platform.fleet.infrastructure;

import com.teecherteamc.platform.fleet.domain.Device;
import com.teecherteamc.platform.fleet.domain.DeviceRepository;
import org.springframework.stereotype.Repository;

@Repository
class DeviceRepositoryImpl implements DeviceRepository {

    private final DeviceJpaRepository jpaRepository;

    DeviceRepositoryImpl(DeviceJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Device save(Device device) {
        return jpaRepository.save(device);
    }
}
