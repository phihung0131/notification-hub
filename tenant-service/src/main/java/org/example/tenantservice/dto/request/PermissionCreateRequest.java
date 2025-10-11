package org.example.tenantservice.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.example.tenantservice.common.enums.PermissionType;

@Getter
@Setter
public class PermissionCreateRequest {
    @NotNull
    private String name;

    @NotNull
    private PermissionType type;

    private String description;

}
