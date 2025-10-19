package org.example.tenantservice.slide.repository;

import org.example.tenantservice.common.enums.PermissionType;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.repository.PermissionRepository;
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
class PermissionRepositoryDataJpaTest {

    @Autowired
    PermissionRepository permissionRepository;

    @Test
    @DisplayName("save and findByNameAndType should work")
    void saveAndFindByNameAndType() {
        Permission p = Permission.builder()
                .name("perm:test")
                .type(PermissionType.API)
                .description("desc")
                .build();

        Permission saved = permissionRepository.save(p);
        assertThat(saved.getId()).isNotNull();

        Optional<Permission> found = permissionRepository.findByNameAndType("perm:test", PermissionType.API);
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("perm:test");
    }

    @Test
    @DisplayName("saving duplicate name+type should violate unique constraint")
    void duplicateNameType_throwsDataIntegrityViolation() {
        Permission a = Permission.builder().name("dup").type(PermissionType.API).build();
        Permission b = Permission.builder().name("dup").type(PermissionType.API).build();

        permissionRepository.saveAndFlush(a);

        assertThatThrownBy(() -> permissionRepository.saveAndFlush(b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByType returns set of permissions")
    void findByType_returnsSet() {
        Permission a = Permission.builder().name("a").type(PermissionType.API).build();
        Permission b = Permission.builder().name("b").type(PermissionType.UI).build();
        permissionRepository.saveAll(Set.of(a, b));

        Set<Permission> apiPerms = permissionRepository.findByType(PermissionType.API);
        assertThat(apiPerms).hasSize(1);
    }
}

