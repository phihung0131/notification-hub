package org.example.tenantservice.repository;

import org.example.tenantservice.common.enums.PermissionType;
import org.example.tenantservice.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, String> {
    Optional<Permission> findByNameAndType(String name, PermissionType type);
    Set<Permission> findByType(PermissionType type);
}
