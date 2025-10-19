package org.example.tenantservice.dto.response;

import lombok.Getter;
import lombok.Setter;
import org.example.tenantservice.model.Permission;

import java.time.Instant;
import java.util.Set;

@Getter
@Setter
public class ApiKeyResponse {
    public String id;
    public String key;
    public Instant expiredAt;
    public boolean revoked;
    public Set<Permission> permissions;
}
