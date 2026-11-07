# ADR 0007: Provider resilience (bulkhead, circuit breaker, 429, failover)

Status: accepted

## Problem

Providers fail in three ways: they error, they rate-limit, and they hang. A hanging provider is the worst case: each call holds a consumer thread until its timeout, and a few such calls starve every other channel on the same worker.

## Options

1. Timeouts only. Bounds each call but still lets one channel use all threads.
2. Resilience4j `TimeLimiter` on a dedicated thread pool per channel. Strong isolation, costs threads and RAM (the box is memory constrained).
3. Resilience4j semaphore `Bulkhead` plus `CircuitBreaker` plus HTTP/SMTP client timeouts, called on the consumer thread.

## Decision

Option 3, in `ResilientInvoker`.

- Timeouts: HTTP connect/read timeouts per provider (`app.delivery.providers.<channel>.*`), SMTP timeouts in `spring.mail.properties`.
- Bulkhead per `(channel, lane)`, `max-concurrent-per-channel-lane` permits, `bulkhead-max-wait` 100 ms. When a provider hangs, at most that many threads are stuck per channel and lane; further calls are rejected in about 100 ms as retryable, and the record moves to a retry tier. Other channels and the other lanes keep their threads.
- Circuit breaker per channel. Counts only retryable failures (5xx, I/O, timeouts). 429 and permanent 4xx are ignored because they say nothing about provider health. Open state fails fast with a retryable error carrying the open-state wait, which the retry router maps to a tier.
- 429: `Retry-After` (seconds) is parsed, falling back to `default-rate-limit-retry-after`. The router chooses the tier that covers it.
- Failover (optional): `app.delivery.providers.<channel>.failover-channel`. On a retryable failure or open circuit the same notification is sent through the failover channel with the same idempotency key. Not applied to 429 or permanent errors.
- Providers are behind the `ChannelProvider` port, built from properties (`type: http|email`, `base-url`, `path`, `api-key`, timeouts). A real provider replaces the mock by changing properties only. A new HTTP vendor with a different payload needs one new `ChannelProvider` class.

## Trade-offs

- A hung call still occupies a thread until the read timeout (3 s default). The bulkhead limits how many, it does not cancel them.
- Failover can change what the user receives (SMS to push). It is off unless configured.
- Breaker state is per instance, not shared across replicas. Each replica learns on its own.
- Email has no provider idempotency; see ADR 0006.
