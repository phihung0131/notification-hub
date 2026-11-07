package org.example.deliveryservice.config;

import java.util.ArrayList;
import java.util.List;

import org.example.deliveryservice.provider.ChannelProvider;
import org.example.deliveryservice.provider.EmailChannelProvider;
import org.example.deliveryservice.provider.HttpChannelProvider;
import org.example.deliveryservice.service.ChannelProviderRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

/** Builds one provider per entry of {@code app.delivery.providers.*}; swap URLs for a real one. */
@Configuration
public class ProviderConfig {

    @Bean
    public ChannelProviderRegistry channelProviderRegistry(
            DeliveryProperties props, ObjectProvider<JavaMailSender> mailSender) {
        List<ChannelProvider> providers = new ArrayList<>();
        props.getProviders()
                .forEach(
                        (channel, cfg) -> {
                            if ("email".equalsIgnoreCase(cfg.getType())) {
                                providers.add(
                                        new EmailChannelProvider(
                                                channel, mailSender.getObject(), cfg.getFrom()));
                            } else {
                                providers.add(
                                        new HttpChannelProvider(
                                                channel,
                                                restClient(cfg),
                                                cfg,
                                                props.getResilience()
                                                        .getDefaultRateLimitRetryAfter()));
                            }
                        });
        return new ChannelProviderRegistry(providers);
    }

    private static RestClient restClient(DeliveryProperties.Provider cfg) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) cfg.getConnectTimeout().toMillis());
        f.setReadTimeout((int) cfg.getReadTimeout().toMillis());
        return RestClient.builder().baseUrl(cfg.getBaseUrl()).requestFactory(f).build();
    }
}
