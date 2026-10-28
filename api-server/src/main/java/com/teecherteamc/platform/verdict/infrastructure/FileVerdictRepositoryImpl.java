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
        // saveAndFlush가 필요한 이유: SubmitFileUseCase.persistOutcome이 이 save() 직후 같은
        // 트랜잭션 안에서 raw JDBC로 pending_quarantine_uploads에 쓰는데, 그 테이블이
        // file_verdicts(sha256)를 FK로 참조한다. Hibernate는 기본적으로 flush를 커밋 시점까지
        // 미루므로, 그냥 save()만 쓰면 raw JDBC insert 시점에 아직 DB에 반영 안 된 행을 참조하게
        // 돼서 FK 위반이 난다.
        return jpaRepository.saveAndFlush(fileVerdict);
    }

    @Override
    public int markEngineVerdictsStale() {
        return jpaRepository.markEngineVerdictsStale();
    }
}
