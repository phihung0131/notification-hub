package org.example.tenantservice.repository;

import java.util.Optional;

import org.example.tenantservice.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {
    Optional<Tenant> findByEmail(String email);

    @Transactional
    @Modifying
    @Query("update Tenant t set t.quotaUsed = t.quotaUsed + :increment where t.id = :tenantId")
    void incrementQuotaUsed(String tenantId, int increment);
}
