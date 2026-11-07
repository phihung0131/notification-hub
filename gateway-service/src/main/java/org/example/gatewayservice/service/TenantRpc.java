package org.example.gatewayservice.service;

import java.time.Duration;

import org.example.proto.tenant.ValidateApiKeyResponse;
import org.example.proto.tenant.ValidateJwtResponse;

/** Blocking RPC seam to tenant-service. Implementations must honour the deadline. */
public interface TenantRpc {

    ValidateApiKeyResponse validateApiKey(String apiKey, Duration deadline);

    ValidateJwtResponse validateJwt(String token, Duration deadline);
}
