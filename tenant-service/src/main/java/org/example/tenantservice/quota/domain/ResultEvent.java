package org.example.tenantservice.quota.domain;

/**
 * Delivery result relevant to quota, decoupled from the Avro wire type.
 *
 * @param messageId notification id (also the reservation id and idempotency key)
 * @param tenantId owning tenant
 * @param outcome SENT counts, FAILED releases
 */
public record ResultEvent(String messageId, String tenantId, Outcome outcome) {

    public enum Outcome {
        SENT,
        FAILED
    }
}
