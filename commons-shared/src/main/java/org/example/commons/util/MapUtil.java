package org.example.commons.util;

import org.springframework.beans.BeanUtils;

/** Utility class for object mapping and property copying operations. */
public class MapUtil {

    /** Private constructor to prevent instantiation of utility class. */
    private MapUtil() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Copies all matching properties from source to target object.
     *
     * @param source the source object to copy from (must not be null)
     * @param target the target object to copy to (must not be null)
     * @throws IllegalArgumentException if source or target is null
     */
    public static void copyProperties(Object source, Object target) {
        BeanUtils.copyProperties(source, target);
    }

    /**
     * Copies matching properties from source to target, ignoring specified properties.
     *
     * @param source the source object to copy from (must not be null)
     * @param target the target object to copy to (must not be null)
     * @param ignoreProperties property names to ignore during copy (can be empty)
     * @throws IllegalArgumentException if source or target is null
     */
    public static void copyProperties(Object source, Object target, String... ignoreProperties) {
        BeanUtils.copyProperties(source, target, ignoreProperties);
    }
}
