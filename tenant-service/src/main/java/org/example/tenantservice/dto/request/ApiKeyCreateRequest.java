package org.example.tenantservice.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Set;

@Getter
@Setter
public class ApiKeyCreateRequest {
    @NotNull
    public Instant expiredAt;

    @NotEmpty
    public Set<String> permissionIds;
}
