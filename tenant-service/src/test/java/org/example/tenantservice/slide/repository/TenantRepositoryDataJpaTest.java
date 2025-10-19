package org.example.tenantservice.slide.repository;

import org.example.tenantservice.common.enums.PermissionType;
import org.example.tenantservice.common.enums.Plan;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.PermissionRepository;
import org.example.tenantservice.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class TenantRepositoryDataJpaTest {

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    PermissionRepository permissionRepository;

    @Test
    @DisplayName("save and findByEmail should work")
    void saveAndFindByEmail() {
        Tenant t = Tenant.builder()
                .email("repo-test@example.com")
                .name("Repo Test")
                .password("pwd")
                .plan(Plan.FREE)
                .build();

        Tenant saved = tenantRepository.save(t);
        assertThat(saved.getId()).isNotNull();

        Optional<Tenant> found = tenantRepository.findByEmail("repo-test@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("repo-test@example.com");
    }

    @Test
    @DisplayName("saving two tenants with same email should violate unique constraint")
    void duplicateEmail_throwsDataIntegrityViolation() {
        Tenant a = Tenant.builder()
                .email("dup@example.com")
                .name("A")
                .password("p")
                .plan(Plan.FREE)
                .build();

        Tenant b = Tenant.builder()
                .email("dup@example.com")
                .name("B")
                .password("p2")
                .plan(Plan.FREE)
                .build();

        tenantRepository.saveAndFlush(a);

        // saving and flushing b should cause a constraint violation in the database
        assertThatThrownBy(() -> tenantRepository.saveAndFlush(b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("saving tenant with permissions persists relationship")
    void saveWithPermissions_persistsRelationship() {
        Permission p = Permission.builder()
                .name("notification:send")
                .type(PermissionType.API)
                .description("send notifications")
                .build();

        Permission savedPerm = permissionRepository.saveAndFlush(p);

        Tenant t = Tenant.builder()
                .email("withperm@example.com")
                .name("WithPerm")
                .password("pwd")
                .plan(Plan.PRO)
                .permissions(Set.of(savedPerm))
                .build();

        Tenant savedTenant = tenantRepository.saveAndFlush(t);

        Optional<Tenant> found = tenantRepository.findById(savedTenant.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getPermissions()).isNotNull();
        assertThat(found.get().getPermissions()).hasSize(1);
        assertThat(found.get().getPermissions()).extracting("name").contains("notification:send");
    }

    @Test
    @DisplayName("new tenant has default quota fields set")
    void defaultQuotaFields_areInitialized() {
        Tenant t = Tenant.builder()
                .email("quota@example.com")
                .name("Quota")
                .password("pwd")
                .plan(Plan.FREE)
                .build();

        Tenant saved = tenantRepository.save(t);
        assertThat(saved.getQuotaLimit()).isNotNull();
        assertThat(saved.getQuotaUsed()).isNotNull();
        assertThat(saved.getQuotaLimit()).isEqualTo(0);
        assertThat(saved.getQuotaUsed()).isEqualTo(0);
    }
}
