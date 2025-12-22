package org.example.tenantservice.repository;

import java.util.Optional;
import java.util.Set;

import org.example.tenantservice.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {
    Set<ApiKey> findByTenantId(String tenantId);

    Optional<ApiKey> findByKey(String key);
}
