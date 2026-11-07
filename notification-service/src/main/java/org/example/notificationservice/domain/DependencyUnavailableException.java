package org.example.notificationservice.domain;

/**
 * A required dependency (quota service, idempotency store, Kafka) is down. The API maps this to
 * 503: the service fails closed instead of risking over-quota or duplicate sends.
 */
public class DependencyUnavailableException extends RuntimeException {
    private final String dependency;

    public DependencyUnavailableException(String dependency, Throwable cause) {
        super(dependency + " unavailable", cause);
        this.dependency = dependency;
    }

    public String getDependency() {
        return dependency;
    }
}
