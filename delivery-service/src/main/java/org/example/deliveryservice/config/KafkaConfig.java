package org.example.deliveryservice.config;

import java.util.Map;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.example.deliveryservice.kafka.DeliveryRecoverer;
import org.example.deliveryservice.kafka.RetryRouter;
import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.state.DeliveryStateRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka wiring. In-place retries are disabled (FixedBackOff 0): every failure goes straight to the
 * recoverer which routes it to a delay-tier topic or the DLQ, so consumer threads never sleep.
 */
@Configuration
public class KafkaConfig {

    @Bean
    @Primary
    public KafkaTemplate<String, Object> kafkaTemplate(KafkaProperties props) {
        return new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(props.buildProducerProperties(null)));
    }

    /** Raw-bytes template so poison (undeserializable) records can be dead-lettered intact. */
    @Bean
    public KafkaTemplate<String, byte[]> bytesKafkaTemplate(KafkaProperties props) {
        Map<String, Object> p = props.buildProducerProperties(null);
        p.put("key.serializer", StringSerializer.class);
        p.put("value.serializer", ByteArraySerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(p));
    }

    @Bean
    public DeliveryRecoverer deliveryRecoverer(
            RetryRouter router,
            DeliveryStateRepository state,
            @Lazy KafkaProducerService producer,
            KafkaTemplate<String, Object> kafkaTemplate,
            @Qualifier("bytesKafkaTemplate") KafkaTemplate<String, byte[]> bytesTemplate) {
        return new DeliveryRecoverer(router, state, producer, kafkaTemplate, bytesTemplate);
    }

    /** Lane listeners: record ack, no in-place retry. */
    @Bean("laneListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> laneListenerFactory(
            ConsumerFactory<Object, Object> consumerFactory, DeliveryRecoverer recoverer) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> f =
                new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(consumerFactory);
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        f.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L)));
        return f;
    }

    /** Retry-tier listeners: manual ack so a record can be nacked until its delay elapsed. */
    @Bean("retryListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> retryListenerFactory(
            ConsumerFactory<Object, Object> consumerFactory, DeliveryRecoverer recoverer) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> f =
                new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(consumerFactory);
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        DefaultErrorHandler eh = new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
        eh.setCommitRecovered(true);
        f.setCommonErrorHandler(eh);
        return f;
    }
}
