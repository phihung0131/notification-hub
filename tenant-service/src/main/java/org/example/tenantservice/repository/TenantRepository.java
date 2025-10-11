package org.example.tenantservice.repository;

import org.example.tenantservice.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, String>
{
    Optional<Tenant> findByEmail(String email);
}

