package org.example.commons.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Unit tests for {@link MapUtil}. */
class MapUtilTest {

    @Test
    @DisplayName("Should copy all properties from source to target")
    void copyProperties_AllProperties_CopiesSuccessfully() {
        // Given
        SourceDto source = new SourceDto("John Doe", "john@example.com", 30);
        TargetDto target = new TargetDto();

        // When
        MapUtil.copyProperties(source, target);

        // Then
        assertEquals("John Doe", target.getName());
        assertEquals("john@example.com", target.getEmail());
        assertEquals(30, target.getAge());
    }

    @Test
    @DisplayName("Should copy null values from source to target")
    void copyProperties_NullValues_CopiesNulls() {
        // Given
        SourceDto source = new SourceDto(null, null, 0);
        TargetDto target = new TargetDto();
        target.setName("Existing Name");
        target.setEmail("existing@example.com");

        // When
        MapUtil.copyProperties(source, target);

        // Then
        assertNull(target.getName());
        assertNull(target.getEmail());
        assertEquals(0, target.getAge()); // primitive int defaults to 0
    }

    @Test
    @DisplayName("Should ignore specified properties when copying")
    void copyProperties_WithIgnoreProperties_IgnoresSpecified() {
        // Given
        SourceDto source = new SourceDto("Jane Doe", "jane@example.com", 25);
        TargetDto target = new TargetDto();
        target.setName("Original Name");
        target.setEmail("original@example.com");
        target.setAge(99);

        // When
        MapUtil.copyProperties(source, target, "name", "age");

        // Then
        assertEquals("Original Name", target.getName()); // ignored, kept original
        assertEquals("jane@example.com", target.getEmail()); // copied
        assertEquals(99, target.getAge()); // ignored, kept original
    }

    @Test
    @DisplayName("Should copy only matching property names and types")
    void copyProperties_MismatchedProperties_CopiesOnlyMatching() {
        // Given
        SourceWithExtra source = new SourceWithExtra("John", "john@example.com", 30, "Extra Field");
        TargetDto target = new TargetDto();

        // When
        MapUtil.copyProperties(source, target);

        // Then
        assertEquals("John", target.getName());
        assertEquals("john@example.com", target.getEmail());
        assertEquals(30, target.getAge());
        // extraField is not copied because TargetDto doesn't have it
    }

    @Test
    @DisplayName("Should handle empty ignore properties array")
    void copyProperties_EmptyIgnoreArray_CopiesAll() {
        // Given
        SourceDto source = new SourceDto("Test", "test@example.com", 20);
        TargetDto target = new TargetDto();

        // When
        MapUtil.copyProperties(source, target, new String[] {});

        // Then
        assertEquals("Test", target.getName());
        assertEquals("test@example.com", target.getEmail());
        assertEquals(20, target.getAge());
    }

    @Test
    @DisplayName("Should throw exception when trying to instantiate")
    void constructor_WhenCalled_ThrowsException() {
        // When & Then
        var exception =
                assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> {
                            var constructor = MapUtil.class.getDeclaredConstructor();
                            constructor.setAccessible(true);
                            constructor.newInstance();
                        });

        // Verify the cause is UnsupportedOperationException
        assertTrue(exception.getCause() instanceof UnsupportedOperationException);
        assertEquals("Utility class - do not instantiate", exception.getCause().getMessage());
    }

    // ===== Test DTOs =====

    /** Source DTO for testing property copying. */
    @Data
    @AllArgsConstructor
    static class SourceDto {
        private String name;
        private String email;
        private int age;
    }

    /** Target DTO for testing property copying. */
    @Data
    static class TargetDto {
        private String name;
        private String email;
        private int age;
    }

    /** Source DTO with extra field for testing partial copying. */
    @Data
    @AllArgsConstructor
    static class SourceWithExtra {
        private String name;
        private String email;
        private int age;
        private String extraField;
    }
}
