package com.teecherteamc.platform.admin.domain;

import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository {

    Optional<AdminUser> findById(UUID adminId);

    Optional<AdminUser> findByUsername(String username);

    boolean existsByUsername(String username);

    AdminUser save(AdminUser adminUser);
}
