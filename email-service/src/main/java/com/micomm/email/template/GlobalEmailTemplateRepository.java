package com.micomm.email.template;

import static com.micomm.email.tenant.TenantTables.table;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.micomm.email.client.dto.TenantDatabaseDto;

/**
 * The club's global email templates (global_templates rows with channel = 'EMAIL'): not part of
 * any module and not tied to a product, so every product of the club sees the same rows.
 * Only club-scoped schemas created since global templates were introduced have the table;
 * {@link #supported} is false for every other schema and the other methods must not be called.
 */
@Component
public class GlobalEmailTemplateRepository {

    private static final RowMapper<EmailTemplateDto> ROW_MAPPER = (rs, rowNum) -> new EmailTemplateDto(
            rs.getObject("id", UUID.class),
            null,
            null,
            rs.getString("name"),
            rs.getString("subject"),
            rs.getString("body"),
            toOffset(rs.getTimestamp("created_at")),
            toOffset(rs.getTimestamp("updated_at")));

    private static final String COLUMNS = "id, name, subject, body, created_at, updated_at";
    private static final String FILTER = " AND club_id = ? AND channel = 'EMAIL'";

    private final JdbcTemplate jdbcTemplate;

    public GlobalEmailTemplateRepository(JdbcTemplate platformJdbcTemplate) {
        this.jdbcTemplate = platformJdbcTemplate;
    }

    public boolean supported(TenantDatabaseDto tenant) {
        return tenant.clubScoped() && Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table(tenant.schemaName(), "global_templates")));
    }

    public List<EmailTemplateDto> findAll(TenantDatabaseDto tenant, UUID clubId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM " + table(tenant.schemaName(), "global_templates")
                + " WHERE true" + FILTER + " ORDER BY created_at DESC", ROW_MAPPER, clubId);
    }

    public Optional<EmailTemplateDto> findById(TenantDatabaseDto tenant, UUID clubId, UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM " + table(tenant.schemaName(), "global_templates")
                + " WHERE id = ?" + FILTER, ROW_MAPPER, id, clubId).stream().findFirst();
    }

    public UUID create(TenantDatabaseDto tenant, UUID clubId, String name, String subject, String body) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO " + table(tenant.schemaName(), "global_templates")
                + " (id, club_id, channel, name, subject, body, created_at, updated_at)"
                + " VALUES (?, ?, 'EMAIL', ?, ?, ?, now(), now())", id, clubId, name, subject, body);
        return id;
    }

    public boolean update(TenantDatabaseDto tenant, UUID clubId, UUID id, String name, String subject, String body) {
        return jdbcTemplate.update("UPDATE " + table(tenant.schemaName(), "global_templates")
                + " SET name = ?, subject = ?, body = ?, updated_at = now() WHERE id = ?" + FILTER,
                name, subject, body, id, clubId) > 0;
    }

    public boolean delete(TenantDatabaseDto tenant, UUID clubId, UUID id) {
        return jdbcTemplate.update("DELETE FROM " + table(tenant.schemaName(), "global_templates")
                + " WHERE id = ?" + FILTER, id, clubId) > 0;
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
