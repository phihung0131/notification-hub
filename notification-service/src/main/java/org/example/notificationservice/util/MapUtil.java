package org.example.notificationservice.util;
import org.springframework.beans.BeanUtils;

public class MapUtil {
    /**
     * Copy properties from source to target
     * @param source the source object
     * @param target the target object
     */
    public static void copyProperties(Object source, Object target) {
        BeanUtils.copyProperties(source, target);
    }

    /**
     * Copy properties from source to target, ignoring specified properties
     * @param source the source object
     * @param target the target object
     * @param ignoreProperties the properties to ignore
     */
    public static void copyProperties(Object source, Object target, String... ignoreProperties) {
        BeanUtils.copyProperties(source, target, ignoreProperties);
    }
}
