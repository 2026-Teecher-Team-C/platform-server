package com.teecherteamc.platform.admin.infrastructure;

import com.teecherteamc.platform.admin.domain.AdminUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AdminUserJpaRepository extends JpaRepository<AdminUser, UUID> {

    Optional<AdminUser> findByUsername(String username);

    boolean existsByUsername(String username);
}
