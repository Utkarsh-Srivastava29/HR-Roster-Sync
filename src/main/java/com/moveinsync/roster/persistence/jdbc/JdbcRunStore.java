package com.moveinsync.roster.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.service.RunStore;
import com.moveinsync.roster.service.model.ReconciliationRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** Runs as JSON documents with the queried columns (tenant, id, status) broken out. */
public class JdbcRunStore implements RunStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<ReconciliationRun> row;

    public JdbcRunStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.row = (rs, i) -> {
            try {
                return json.readValue(rs.getString("body"), ReconciliationRun.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("corrupt run record", e);
            }
        };
    }

    @Override
    public void save(ReconciliationRun r) {
        String body;
        try {
            body = json.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("""
                INSERT INTO reconciliation_run (tenant_id, run_id, status, created_at, body) VALUES (?,?,?,?,?)
                ON CONFLICT (tenant_id, run_id) DO UPDATE SET status = EXCLUDED.status, body = EXCLUDED.body
                """, r.tenantId(), r.runId(), r.status().name(), Timestamp.from(r.createdAt()), body);
    }

    @Override
    public Optional<ReconciliationRun> find(String tenantId, String runId) {
        return jdbc.query("SELECT body FROM reconciliation_run WHERE tenant_id = ? AND run_id = ?", row, tenantId, runId)
                .stream().findFirst();
    }

    @Override
    public List<ReconciliationRun> list(String tenantId, int limit) {
        return jdbc.query("SELECT body FROM reconciliation_run WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?", row, tenantId, limit);
    }

    @Override
    public List<ReconciliationRun> findByStatus(ReconciliationRun.Status status) {
        return jdbc.query("SELECT body FROM reconciliation_run WHERE status = ?", row, status.name());
    }
}
