package org.example.tenantservice.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.example.tenantservice.model.ApiKey;
import org.example.tenantservice.model.Permission;
import org.example.tenantservice.model.ProcessedEvent;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.quota.adapters.jdbc.JdbcQuotaStore;
import org.example.tenantservice.quota.application.port.QuotaStorePort.CommitItem;
import org.example.tenantservice.quota.domain.CommitOutcome;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs the Flyway migrations on a REAL PostgreSQL, validates them against the JPA entities (what
 * ddl-auto=validate does at startup) and exercises the quota SQL. Skipped unless {@code
 * -Dtest.pg.url=jdbc:postgresql://localhost:5441/tdb -Dtest.pg.user=postgres -Dtest.pg.password=pw}
 * is set.
 */
class PostgresQuotaStoreTest {

    private static DataSource ds;
    private static JdbcTemplate jdbc;
    private static JdbcQuotaStore store;

    @BeforeAll
    static void migrate() throws Exception {
        String url = System.getProperty("test.pg.url");
        assumeTrue(url != null, "no -Dtest.pg.url, skipping Postgres tests");
        String user = System.getProperty("test.pg.user", "postgres");
        String pw = System.getProperty("test.pg.password", "pw");
        try (Connection c = DriverManager.getConnection(url, user, pw)) {
            c.createStatement().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        }
        DriverManagerDataSource d = new DriverManagerDataSource(url, user, pw);
        ds = d;
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
                        .addAnnotatedClass(Tenant.class)
                        .addAnnotatedClass(Permission.class)
                        .addAnnotatedClass(ApiKey.class)
                        .addAnnotatedClass(ProcessedEvent.class)
                        .buildMetadata();
        md.buildSessionFactory().close(); // validate: throws if entities and migrations disagree

        jdbc = new JdbcTemplate(ds);
        store = new JdbcQuotaStore(jdbc);
    }

    private static String tenant(int limit, int used) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO tenants (id, name, plan, status, email, password, quota_limit,"
                        + " quota_used, created_at, updated_at)"
                        + " VALUES (?, 'n', 'FREE', 'ACTIVE', ?, 'p', ?, ?, now(), now())",
                id,
                id + "@x.com",
                limit,
                used);
        return id;
    }

    @Test
    void commitIsIdempotentAndCappedAtLimit() {
        String t = tenant(3, 0);
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        String d = UUID.randomUUID().toString();

        Map<String, CommitOutcome> r1 =
                store.commitBatch(List.of(new CommitItem(a, t), new CommitItem(b, t)));
        assertEquals(CommitOutcome.COMMITTED, r1.get(a));
        assertEquals(2, store.find(t).orElseThrow().used());

        // redelivery of a + new c, d: a is DUPLICATE, c fits, d exceeds the limit
        Map<String, CommitOutcome> r2 =
                store.commitBatch(
                        List.of(new CommitItem(a, t), new CommitItem(c, t), new CommitItem(d, t)));
        assertEquals(CommitOutcome.DUPLICATE, r2.get(a));
        assertEquals(CommitOutcome.COMMITTED, r2.get(c));
        assertEquals(CommitOutcome.LIMIT_REACHED, r2.get(d));
        assertEquals(3, store.find(t).orElseThrow().used());

        // d was recorded as processed: a later redelivery stays a duplicate
        assertEquals(
                CommitOutcome.DUPLICATE, store.commitBatch(List.of(new CommitItem(d, t))).get(d));
        assertEquals(3, store.find(t).orElseThrow().used());
    }

    @Test
    void unknownTenantIsReportedAndNothingIsRecorded() {
        String id = UUID.randomUUID().toString();
        assertEquals(
                CommitOutcome.UNKNOWN_TENANT,
                store.commitBatch(List.of(new CommitItem(id, "ghost"))).get(id));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM processed_events WHERE message_id = ?",
                        Integer.class,
                        id));
    }

    @Test
    void concurrentCommitsOfSameMessageCountOnce() throws Exception {
        String t = tenant(-1, 0);
        String m = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Map<String, CommitOutcome>>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            fs.add(pool.submit(() -> store.commitBatch(List.of(new CommitItem(m, t)))));
        }
        for (Future<Map<String, CommitOutcome>> f : fs) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, store.find(t).orElseThrow().used());
    }

    @Test
    void cleanupDeletesOldRecordsOnly() {
        String t = tenant(-1, 0);
        String m = UUID.randomUUID().toString();
        store.commitBatch(List.of(new CommitItem(m, t)));
        assertEquals(0, store.deleteProcessedBefore(Instant.now().minusSeconds(3600)));
    }
}
