package org.example.tenantservice.repository;

import org.example.tenantservice.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {
    Set<ApiKey> findByTenantId(String tenantId);
    Optional<ApiKey> findByKey(String key);
}
