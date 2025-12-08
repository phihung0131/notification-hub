package com.example.deliveryservice.config;

import com.example.deliveryservice.config.logger.GrpcClientLoggingInterceptor;
import com.example.deliveryservice.config.logger.GrpcServerLoggingInterceptor;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelConfigurer;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcConfig {

    /**
     * Register gRPC Server Interceptor
     */
    @Bean
    public GrpcServerConfigurer grpcServerConfigurer(GrpcServerLoggingInterceptor serverInterceptor) {
        return serverBuilder -> serverBuilder.intercept(serverInterceptor);
    }

    /**
     * Register gRPC Client Interceptor
     */
    @Bean
    public GrpcChannelConfigurer grpcChannelConfigurer() {
        return (channelBuilder, channelName) ->
                channelBuilder.intercept(new GrpcClientLoggingInterceptor());
    }

}