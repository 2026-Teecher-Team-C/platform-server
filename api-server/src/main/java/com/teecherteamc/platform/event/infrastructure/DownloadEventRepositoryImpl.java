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
        // saveAndFlush가 필요한 이유: ReportEventUseCase가 이 save() 직후 같은 트랜잭션 안에서
        // raw JDBC로 quarantine_files에 쓰는데, 그 테이블이 download_events(event_id)를 FK로
        // 참조한다. Hibernate는 flush를 커밋 시점까지 미루므로, 그냥 save()만 쓰면 raw JDBC insert
        // 시점에 아직 DB에 반영 안 된 행을 참조하게 돼서 FK 위반이 난다.
        return jpaRepository.saveAndFlush(event);
    }
}
