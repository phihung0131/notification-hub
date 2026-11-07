package org.example.notificationservice.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.events.NotificationEvent;
import org.example.notificationservice.adapters.jdbc.JdbcNotificationStatusAdapter;
import org.example.notificationservice.adapters.jdbc.JdbcNotificationWriter;
import org.example.notificationservice.adapters.outbox.OutboxRelay;
import org.example.notificationservice.application.port.NotificationStatusPort.Result;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.example.notificationservice.domain.DuplicateIdempotencyKeyException;
import org.example.notificationservice.domain.NewNotification;
import org.example.notificationservice.model.Channel;
import org.example.notificationservice.model.Notification;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Runs the Flyway migrations on a REAL PostgreSQL, validates them against the JPA entities (what
 * ddl-auto=validate does at startup) and exercises the write path SQL: unique idempotency key,
 * outbox in the same transaction, FOR UPDATE SKIP LOCKED relay, guarded status transitions. Skipped
 * unless {@code -Dtest.pg.url=jdbc:postgresql://localhost:5441/ndb} is set.
 */
class PostgresWritePathTest {

    private static DriverManagerDataSource ds;
    private static JdbcTemplate jdbc;
    private static JdbcNotificationWriter writer;
    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeAll
    static void migrate() throws Exception {
        String url = System.getProperty("test.pg.url");
        assumeTrue(url != null, "no -Dtest.pg.url, skipping Postgres tests");
        String user = System.getProperty("test.pg.user", "postgres");
        String pw = System.getProperty("test.pg.password", "pw");
        try (Connection c = DriverManager.getConnection(url, user, pw)) {
            c.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        }
        ds = new DriverManagerDataSource(url, user, pw);
        Flyway.configure()
                .dataSource(ds)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        StandardServiceRegistry reg =
                new StandardServiceRegistryBuilder()
                        .applySetting("hibernate.connection.url", url)
                        .applySetting("hibernate.connection.username", user)
                        .applySetting("hibernate.connection.password", pw)
                        .applySetting("hibernate.hbm2ddl.auto", "validate")
                        .applySetting(
                                "hibernate.physical_naming_strategy",
                                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                        .build();
        Metadata md =
                new MetadataSources(reg)
                        .addAnnotatedClass(Notification.class)
                        .addAnnotatedClass(Channel.class)
                        .buildMetadata();
        md.buildSessionFactory().close();

        jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO channels (id, name, code, active) VALUES ('c1', 'Email', 'email', true)");
        writer = new JdbcNotificationWriter(jdbc, MAPPER, "notification.requested");
    }

    private static NewNotification n(String tenant, String key, String fp) {
        return new NewNotification(
                UUID.randomUUID(),
                tenant,
                "email",
                "a@b.com",
                "s",
                "c",
                "NORMAL",
                key,
                fp,
                Instant.now());
    }

    @Test
    void notificationAndOutboxAreWrittenAndDuplicateKeyIsRejected() {
        String t = "t-" + UUID.randomUUID();
        NewNotification first = n(t, "key-1", "fp");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.executeWithoutResult(s -> writer.insertWithOutbox(first));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                        Integer.class,
                        first.id()));

        assertThrows(
                DuplicateIdempotencyKeyException.class,
                () -> tx.executeWithoutResult(s -> writer.insertWithOutbox(n(t, "key-1", "fp"))));
        // the failed transaction wrote neither a notification nor an outbox row
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM notifications WHERE tenant_id = ?",
                        Integer.class,
                        t));
        assertEquals(
                first.id().toString(), writer.findByIdempotencyKey(t, "key-1").orElseThrow().id());

        // same key in another tenant is fine; null key is unconstrained
        tx.executeWithoutResult(s -> writer.insertWithOutbox(n("other-" + t, "key-1", "fp")));
        tx.executeWithoutResult(s -> writer.insertWithOutbox(n(t, null, "fp")));
        tx.executeWithoutResult(s -> writer.insertWithOutbox(n(t, null, "fp")));
    }

    @Test
    void concurrentSameKeyInsertsExactlyOneRow() throws Exception {
        String t = "t-" + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger dups = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            fs.add(
                    pool.submit(
                            () -> {
                                go.await();
                                try {
                                    tx.executeWithoutResult(
                                            s -> writer.insertWithOutbox(n(t, "storm", "fp")));
                                } catch (DuplicateIdempotencyKeyException e) {
                                    dups.incrementAndGet();
                                }
                                return null;
                            }));
        }
        go.countDown();
        for (Future<?> f : fs) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM notifications WHERE tenant_id = ?",
                        Integer.class,
                        t));
        assertEquals(31, dups.get());
    }

    @Test
    void insertIfAbsentIsIdempotent() {
        NewNotification x = n("t-" + UUID.randomUUID(), "kf-key", "fp");
        assertTrue(writer.insertIfAbsent(x));
        assertEquals(false, writer.insertIfAbsent(x));
        assertEquals(false, writer.insertIfAbsent(n(x.tenantId(), "kf-key", "fp")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusTransitionsNeverRegress() {
        NewNotification x = n("t-" + UUID.randomUUID(), null, "fp");
        writer.insertIfAbsent(x);
        JdbcNotificationStatusAdapter status = new JdbcNotificationStatusAdapter(jdbc);
        assertEquals(Result.UPDATED, status.transition(x.id(), NotificationStatus.SENT));
        assertEquals(Result.IGNORED, status.transition(x.id(), NotificationStatus.SENT));
        assertEquals(Result.IGNORED, status.transition(x.id(), NotificationStatus.FAILED));
        assertEquals(Result.IGNORED, status.transition(x.id(), NotificationStatus.PENDING));
        assertEquals(
                Result.NOT_FOUND, status.transition(UUID.randomUUID(), NotificationStatus.SENT));
        assertEquals(
                "SENT",
                jdbc.queryForObject(
                        "SELECT status FROM notifications WHERE id = ?", String.class, x.id()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesEachOutboxRowOnceAndKeysByTenant() {
        jdbc.update("DELETE FROM outbox_events");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String t = "t-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            tx.executeWithoutResult(s -> writer.insertWithOutbox(n(t, null, "fp")));
        }
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        List<String> keys = Collections.synchronizedList(new ArrayList<>());
        List<Object> values = Collections.synchronizedList(new ArrayList<>());
        when(kafka.send(anyString(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            keys.add(inv.getArgument(1));
                            values.add(inv.getArgument(2));
                            return CompletableFuture.completedFuture(null);
                        });
        OutboxRelay relay = new OutboxRelay(jdbc, tx, kafka, MAPPER, 3, 5000);

        assertEquals(3, relay.pollOnce());
        assertEquals(2, relay.pollOnce());
        assertEquals(0, relay.pollOnce());
        assertEquals(5, keys.size());
        assertTrue(keys.stream().allMatch(t::equals), "keyed by tenantId");
        assertTrue(values.stream().allMatch(v -> v instanceof NotificationEvent));
        assertEquals("NORMAL", ((NotificationEvent) values.get(0)).getPriority());
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayFailureRollsBackSoRowsAreRetried() {
        jdbc.update("DELETE FROM outbox_events");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String t = "t-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> writer.insertWithOutbox(n(t, null, "fp")));
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        OutboxRelay relay = new OutboxRelay(jdbc, tx, kafka, MAPPER, 10, 5000);
        assertThrows(IllegalStateException.class, relay::pollOnce);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_events WHERE sent_at IS NULL", Integer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoRelaysNeverTakeTheSameRows() throws Exception {
        jdbc.update("DELETE FROM outbox_events");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String t = "t-" + UUID.randomUUID();
        for (int i = 0; i < 40; i++) {
            tx.executeWithoutResult(s -> writer.insertWithOutbox(n(t, null, "fp")));
        }
        List<Object> sent = Collections.synchronizedList(new ArrayList<>());
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            sent.add(((NotificationEvent) inv.getArgument(2)).getId());
                            return CompletableFuture.completedFuture(null);
                        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> a = pool.submit(() -> new OutboxRelay(jdbc, tx, kafka, MAPPER, 10, 5000).poll());
        Future<?> b = pool.submit(() -> new OutboxRelay(jdbc, tx, kafka, MAPPER, 10, 5000).poll());
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(40, sent.size());
        assertEquals(40, sent.stream().distinct().count(), "no row published twice");
    }
}
