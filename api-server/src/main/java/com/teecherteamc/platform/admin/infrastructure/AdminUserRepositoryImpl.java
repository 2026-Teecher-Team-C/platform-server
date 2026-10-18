package com.teecherteamc.platform.admin.infrastructure;

import com.teecherteamc.platform.admin.domain.AdminUser;
import com.teecherteamc.platform.admin.domain.AdminUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class AdminUserRepositoryImpl implements AdminUserRepository {

    private final AdminUserJpaRepository jpaRepository;

    AdminUserRepositoryImpl(AdminUserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<AdminUser> findById(UUID adminId) {
        return jpaRepository.findById(adminId);
    }

    @Override
    public Optional<AdminUser> findByUsername(String username) {
        return jpaRepository.findByUsername(username);
    }

    @Override
    public boolean existsByUsername(String username) {
        return jpaRepository.existsByUsername(username);
    }

    @Override
    public AdminUser save(AdminUser adminUser) {
        return jpaRepository.save(adminUser);
    }
}
