package com.teecherteamc.platform.appeal.infrastructure;

import com.teecherteamc.platform.appeal.domain.Appeal;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 소명용 DB 접근 도구. 같은 패키지의 Adapter만 쓰도록 public을 붙이지 않았다. */
interface AppealJpaRepository extends JpaRepository<Appeal, UUID> {

    Optional<Appeal> findByEventId(UUID eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appeal a where a.appealId = :appealId")
    Optional<Appeal> findByIdForUpdate(@Param("appealId") UUID appealId);
}
