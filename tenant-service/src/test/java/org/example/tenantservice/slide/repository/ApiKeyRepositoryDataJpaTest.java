package org.example.tenantservice.slide.repository;

import org.example.tenantservice.common.enums.PermissionType;
import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.ApiKeyRepository;
import org.example.tenantservice.repository.PermissionRepository;
import org.example.tenantservice.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ApiKeyRepositoryDataJpaTest {

    @Autowired
    ApiKeyRepository apiKeyRepository;

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    PermissionRepository permissionRepository;

    @Test
    @DisplayName("save and findByTenantId should work")
    void saveAndFindByTenantId() {
        Tenant t = Tenant.builder().email("ak-tenant@example.com").name("TenantA").password("pwd").plan(Plan.FREE).build();
        Tenant savedTenant = tenantRepository.saveAndFlush(t);

        Permission p = Permission.builder().name("notification:send").type(PermissionType.API).build();
        Permission savedPerm = permissionRepository.saveAndFlush(p);

        ApiKey k = ApiKey.builder().key("k-key").tenant(savedTenant).revoked(false).expiredAt(Instant.now().plusSeconds(3600)).permissions(Set.of(savedPerm)).build();
        apiKeyRepository.saveAndFlush(k);

        Set<ApiKey> found = apiKeyRepository.findByTenantId(savedTenant.getId());
        assertThat(found).isNotEmpty();
        assertThat(found.iterator().next().getKey()).isEqualTo("k-key");
    }
}

