package com.teecherteamc.platform.fleet.domain;

import java.util.Optional;

public interface EnrollmentTokenRepository {

    EnrollmentToken save(EnrollmentToken enrollmentToken);

    Optional<EnrollmentToken> findByEnrollmentTokenHash(String enrollmentTokenHash);
}
