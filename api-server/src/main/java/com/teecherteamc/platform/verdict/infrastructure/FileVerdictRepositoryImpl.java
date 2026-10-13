package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.FileVerdict;
import com.teecherteamc.platform.verdict.domain.FileVerdictRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class FileVerdictRepositoryImpl implements FileVerdictRepository {

    private final FileVerdictJpaRepository jpaRepository;

    FileVerdictRepositoryImpl(FileVerdictJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<FileVerdict> findBySha256(String sha256) {
        return jpaRepository.findById(sha256);
    }

    @Override
    public FileVerdict save(FileVerdict fileVerdict) {
        return jpaRepository.save(fileVerdict);
    }
}
