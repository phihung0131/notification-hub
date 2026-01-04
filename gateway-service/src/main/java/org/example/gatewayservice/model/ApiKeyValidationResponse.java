package org.example.gatewayservice.model;

import java.util.Set;

public record ApiKeyValidationResponse(String tenantId, Set<String> permissions) {}
