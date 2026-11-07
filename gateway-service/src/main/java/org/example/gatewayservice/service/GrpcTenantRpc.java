package org.example.gatewayservice.service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.example.proto.tenant.TenantServiceGrpc;
import org.example.proto.tenant.ValidateApiKeyRequest;
import org.example.proto.tenant.ValidateApiKeyResponse;
import org.example.proto.tenant.ValidateJwtRequest;
import org.example.proto.tenant.ValidateJwtResponse;
import org.springframework.stereotype.Component;

import net.devh.boot.grpc.client.inject.GrpcClient;

/** gRPC implementation; every call carries a per-call deadline. */
@Component
public class GrpcTenantRpc implements TenantRpc {

    @GrpcClient("tenant-service")
    private TenantServiceGrpc.TenantServiceBlockingStub stub;

    @Override
    public ValidateApiKeyResponse validateApiKey(String apiKey, Duration deadline) {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .validateApiKey(ValidateApiKeyRequest.newBuilder().setApiKey(apiKey).build());
    }

    @Override
    public ValidateJwtResponse validateJwt(String token, Duration deadline) {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .validateJwt(ValidateJwtRequest.newBuilder().setToken(token).build());
    }
}
