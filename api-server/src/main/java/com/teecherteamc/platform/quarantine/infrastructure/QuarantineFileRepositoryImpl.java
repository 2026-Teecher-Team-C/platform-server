package com.teecherteamc.platform.quarantine.infrastructure;

import com.teecherteamc.platform.quarantine.domain.QuarantineFile;
import com.teecherteamc.platform.quarantine.domain.QuarantineFileRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class QuarantineFileRepositoryImpl implements QuarantineFileRepository {

    private final QuarantineFileJpaRepository jpaRepository;

    QuarantineFileRepositoryImpl(QuarantineFileJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<QuarantineFile> findById(UUID quarantineId) {
        return jpaRepository.findById(quarantineId);
    }

    @Override
    public QuarantineFile save(QuarantineFile quarantineFile) {
        return jpaRepository.save(quarantineFile);
    }
}
