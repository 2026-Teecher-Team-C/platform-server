package com.teecherteamc.platform.event.infrastructure;

import com.teecherteamc.platform.event.domain.DownloadEvent;
import com.teecherteamc.platform.event.domain.DownloadEventRepository;
import org.springframework.stereotype.Repository;

@Repository
class DownloadEventRepositoryImpl implements DownloadEventRepository {

    private final DownloadEventJpaRepository jpaRepository;

    DownloadEventRepositoryImpl(DownloadEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public DownloadEvent save(DownloadEvent event) {
        return jpaRepository.save(event);
    }
}
