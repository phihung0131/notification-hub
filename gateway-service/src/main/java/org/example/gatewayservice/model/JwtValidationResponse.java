package org.example.gatewayservice.model;

import java.util.Set;

public record JwtValidationResponse(String tenantId, Set<String> permissions) {}
