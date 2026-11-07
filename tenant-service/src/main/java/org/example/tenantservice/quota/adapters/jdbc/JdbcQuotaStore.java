package org.example.tenantservice.quota.adapters.jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.example.tenantservice.quota.application.port.QuotaStorePort;
import org.example.tenantservice.quota.domain.CommitOutcome;
import org.example.tenantservice.quota.domain.QuotaSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL adapter for durable quota truth. Uses plain SQL on purpose: {@code INSERT .. ON
 * CONFLICT DO NOTHING} and {@code SELECT .. FOR UPDATE} give the exact semantics we need. The
 * {@code @Transactional} boundary is on this bean (called from another bean), so the proxy applies.
 */
@Component
public class JdbcQuotaStore implements QuotaStorePort {

    static final String EVENT_TYPE = "NOTIFICATION_RESULT";

    private final JdbcTemplate jdbc;

    public JdbcQuotaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuotaSnapshot> find(String tenantId) {
        List<QuotaSnapshot> rows =
                jdbc.query(
                        "SELECT quota_limit, quota_used FROM tenants WHERE id = ?",
                        (rs, i) -> new QuotaSnapshot(rs.getInt(1), rs.getInt(2), 0),
                        tenantId);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> allTenantIds() {
        return jdbc.queryForList("SELECT id FROM tenants", String.class);
    }

    @Override
    @Transactional
    public Map<String, CommitOutcome> commitBatch(Collection<CommitItem> items) {
        Map<String, CommitOutcome> outcomes = new LinkedHashMap<>();
        // sorted tenant order => consistent row-lock order => no deadlocks between consumers
        Map<String, List<CommitItem>> byTenant = new TreeMap<>();
        for (CommitItem item : items) {
            byTenant.computeIfAbsent(item.tenantId(), k -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<CommitItem>> e : byTenant.entrySet()) {
            String tenantId = e.getKey();
            List<QuotaSnapshot> rows =
                    jdbc.query(
                            "SELECT quota_limit, quota_used FROM tenants WHERE id = ? FOR UPDATE",
                            (rs, i) -> new QuotaSnapshot(rs.getInt(1), rs.getInt(2), 0),
                            tenantId);
            if (rows.isEmpty()) {
                e.getValue()
                        .forEach(i -> outcomes.put(i.messageId(), CommitOutcome.UNKNOWN_TENANT));
                continue;
            }
            QuotaSnapshot q = rows.get(0);
            List<CommitItem> fresh = new ArrayList<>();
            for (CommitItem item : e.getValue()) {
                int inserted =
                        jdbc.update(
                                "INSERT INTO processed_events (id, message_id, tenant_id, event_type,"
                                        + " processed_at) VALUES (?, ?, ?, ?, ?)"
                                        + " ON CONFLICT (message_id) DO NOTHING",
                                UUID.randomUUID().toString(),
                                item.messageId(),
                                tenantId,
                                EVENT_TYPE,
                                Timestamp.from(Instant.now()));
                if (inserted == 1) {
                    fresh.add(item);
                } else {
                    outcomes.put(item.messageId(), CommitOutcome.DUPLICATE);
                }
            }
            int allowed =
                    q.unlimited()
                            ? fresh.size()
                            : Math.max(0, Math.min(fresh.size(), q.limit() - q.used()));
            if (allowed > 0) {
                jdbc.update(
                        "UPDATE tenants SET quota_used = quota_used + ?, updated_at = now()"
                                + " WHERE id = ?",
                        allowed,
                        tenantId);
            }
            for (int i = 0; i < fresh.size(); i++) {
                outcomes.put(
                        fresh.get(i).messageId(),
                        i < allowed ? CommitOutcome.COMMITTED : CommitOutcome.LIMIT_REACHED);
            }
        }
        return outcomes;
    }

    @Override
    @Transactional
    public int deleteProcessedBefore(Instant cutoff) {
        return jdbc.update(
                "DELETE FROM processed_events WHERE processed_at < ?", Timestamp.from(cutoff));
    }
}
