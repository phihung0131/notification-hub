package com.example.deliveryservice.config.logger;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * gRPC Server Interceptor - logs incoming gRPC requests
 */
@Slf4j
@Component
public class GrpcServerLoggingInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        String serviceName = call.getMethodDescriptor().getServiceName();
        String methodName = call.getMethodDescriptor().getBareMethodName();

        Instant startTime = Instant.now();

        log.info("[INCOMING gRPC SERVER] Service: {} | Method: {}", serviceName, methodName);

        ServerCall<ReqT, RespT> loggingCall = new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long duration = Duration.between(startTime, Instant.now()).toMillis();

                if (status.isOk()) {
                    log.info("[COMPLETED gRPC SERVER] Method: {} | Status: OK | Duration: {}ms",
                            methodName, duration);
                } else {
                    log.error("[COMPLETED gRPC SERVER] Method: {} | Status: {} | Description: {} | Duration: {}ms",
                            methodName, status.getCode(), status.getDescription(), duration);
                }

                super.close(status, trailers);
            }
        };

        return next.startCall(loggingCall, headers);
    }
}