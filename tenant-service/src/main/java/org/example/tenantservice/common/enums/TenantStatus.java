package org.example.tenantservice.common.enums;

/** Enum representing the status of a tenant */
public enum TenantStatus {
    ACTIVE, // Tenant is active and can use the service
    SUSPENDED // Tenant is temporarily suspended (e.g., payment issues)
}
