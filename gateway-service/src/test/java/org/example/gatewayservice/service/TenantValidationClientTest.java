package org.example.gatewayservice.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.gatewayservice.exception.InvalidCredentialsException;
import org.example.gatewayservice.exception.TenantServiceUnavailableException;
import org.example.proto.tenant.ValidateApiKeyResponse;
import org.example.proto.tenant.ValidateJwtResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.grpc.Status;
import reactor.test.StepVerifier;

class TenantValidationClientTest {

    interface ApiKeyCall {
        ValidateApiKeyResponse call(String key);
    }

    private static TenantValidationClient client(ApiKeyCall call, Duration ttl) {
        TenantRpc rpc =
                new TenantRpc() {
                    @Override
                    public ValidateApiKeyResponse validateApiKey(String k, Duration d) {
                        return call.call(k);
                    }

                    @Override
                    public ValidateJwtResponse validateJwt(String t, Duration d) {
                        return ValidateJwtResponse.newBuilder()
                                .setIsValid(true)
                                .setTenantId("jwt-tenant")
                                .build();
                    }
                };
        return new TenantValidationClient(
                rpc, Duration.ofSeconds(1), ttl, Duration.ofSeconds(5), 100);
    }

    private static ValidateApiKeyResponse valid() {
        return ValidateApiKeyResponse.newBuilder()
                .setIsValid(true)
                .setTenantId("tenant-1")
                .addPermissions("SEND")
                .build();
    }

    @Test
    void validKeyReturnsTenant() {
        TenantValidationClient c = client(k -> valid(), Duration.ofSeconds(30));
        StepVerifier.create(c.validateApiKey("key"))
                .expectNextMatches(r -> r.tenantId().equals("tenant-1"))
                .verifyComplete();
    }

    @Test
    void validKeyIsCachedWithinTtl() {
        AtomicInteger calls = new AtomicInteger();
        TenantValidationClient c =
                client(
                        k -> {
                            calls.incrementAndGet();
                            return valid();
                        },
                        Duration.ofSeconds(30));
        c.validateApiKey("key").block();
        c.validateApiKey("key").block();
        c.validateApiKey("key").block();
        Assertions.assertEquals(1, calls.get());
    }

    @Test
    void cacheExpiresAfterTtl() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TenantValidationClient c =
                client(
                        k -> {
                            calls.incrementAndGet();
                            return valid();
                        },
                        Duration.ofMillis(50));
        c.validateApiKey("key").block();
        Thread.sleep(120);
        c.validateApiKey("key").block();
        Assertions.assertEquals(2, calls.get());
    }

    @Test
    void invalidKeyIs401Class_andNegativelyCached() {
        AtomicInteger calls = new AtomicInteger();
        TenantValidationClient c =
                client(
                        k -> {
                            calls.incrementAndGet();
                            return ValidateApiKeyResponse.newBuilder().setIsValid(false).build();
                        },
                        Duration.ofSeconds(30));
        StepVerifier.create(c.validateApiKey("bad"))
                .expectError(InvalidCredentialsException.class)
                .verify();
        StepVerifier.create(c.validateApiKey("bad"))
                .expectError(InvalidCredentialsException.class)
                .verify();
        Assertions.assertEquals(1, calls.get());
    }

    @Test
    void grpcUnavailableIsServiceUnavailable_andNotCached() {
        AtomicInteger calls = new AtomicInteger();
        TenantValidationClient c =
                client(
                        k -> {
                            if (calls.incrementAndGet() == 1) {
                                throw Status.UNAVAILABLE.asRuntimeException();
                            }
                            return valid();
                        },
                        Duration.ofSeconds(30));
        StepVerifier.create(c.validateApiKey("key"))
                .expectError(TenantServiceUnavailableException.class)
                .verify();
        // recovery: the failure was not cached
        StepVerifier.create(c.validateApiKey("key")).expectNextCount(1).verifyComplete();
    }

    @Test
    void deadlineExceededIsServiceUnavailable() {
        TenantValidationClient c =
                client(
                        k -> {
                            throw Status.DEADLINE_EXCEEDED.asRuntimeException();
                        },
                        Duration.ofSeconds(30));
        StepVerifier.create(c.validateApiKey("key"))
                .expectError(TenantServiceUnavailableException.class)
                .verify();
    }

    @Test
    void grpcUnauthenticatedIsInvalidCredentials() {
        TenantValidationClient c =
                client(
                        k -> {
                            throw Status.UNAUTHENTICATED.asRuntimeException();
                        },
                        Duration.ofSeconds(30));
        StepVerifier.create(c.validateApiKey("key"))
                .expectError(InvalidCredentialsException.class)
                .verify();
    }

    @Test
    void blockingCallRunsOffTheCallerThread() {
        String caller = Thread.currentThread().getName();
        java.util.concurrent.atomic.AtomicReference<String> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        TenantValidationClient c =
                client(
                        k -> {
                            seen.set(Thread.currentThread().getName());
                            return valid();
                        },
                        Duration.ofSeconds(30));
        c.validateApiKey("key").block();
        Assertions.assertNotEquals(caller, seen.get());
    }

    @Test
    void jwtValidates() {
        TenantValidationClient c = client(k -> valid(), Duration.ofSeconds(30));
        StepVerifier.create(c.validateJwt("tok"))
                .expectNextMatches(r -> r.tenantId().equals("jwt-tenant"))
                .verifyComplete();
    }
}
