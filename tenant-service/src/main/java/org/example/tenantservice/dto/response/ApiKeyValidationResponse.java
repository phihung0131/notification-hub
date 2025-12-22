package org.example.tenantservice.dto.response;

import java.util.Set;

public record ApiKeyValidationResponse(String tenantId, Set<String> permissions) {}
