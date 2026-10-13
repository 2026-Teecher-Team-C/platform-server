package com.teecherteamc.platform.fleet.infrastructure;

import com.teecherteamc.platform.fleet.domain.EnrollmentToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface EnrollmentTokenJpaRepository extends JpaRepository<EnrollmentToken, UUID> {

    Optional<EnrollmentToken> findByEnrollmentTokenHash(String enrollmentTokenHash);
}
