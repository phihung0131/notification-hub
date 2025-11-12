package org.example.notificationservice.config.logger;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * gRPC Client Interceptor - logs outgoing gRPC requests
 */
@Slf4j
public class GrpcClientLoggingInterceptor implements ClientInterceptor {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next
    ) {
        String serviceName = method.getServiceName();
        String methodName = method.getBareMethodName();

        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)
        ) {
            private Instant startTime;

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                startTime = Instant.now();
                log.info("[OUTGOING gRPC CLIENT] Service: {} | Method: {}", serviceName, methodName);

                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                        responseListener
                ) {
                    @Override
                    public void onMessage(RespT message) {
                        super.onMessage(message);
                        log.debug("[RESPONSE gRPC CLIENT] Method: {} | Success", methodName);
                    }

                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        long duration = Duration.between(startTime, Instant.now()).toMillis();

                        if (status.isOk()) {
                            log.info("[COMPLETED gRPC CLIENT] Method: {} | Status: OK | Duration: {}ms",
                                    methodName, duration);
                        } else {
                            log.error("[COMPLETED gRPC CLIENT] Method: {} | Status: {} | Description: {} | Duration: {}ms",
                                    methodName, status.getCode(), status.getDescription(), duration);
                        }

                        super.onClose(status, trailers);
                    }
                }, headers);
            }
        };
    }
}