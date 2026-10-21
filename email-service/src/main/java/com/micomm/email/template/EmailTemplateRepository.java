package com.micomm.email.template;

import static com.micomm.email.tenant.TenantTables.table;

import com.micomm.email.client.dto.TenantDatabaseDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Email templates in either tenant layout:
 * <ul>
 * <li>legacy ("1"): email_templates, shared by all of the club's products
 * (unchanged behaviour)</li>
 * <li>notifications ("2"): notification_templates rows with channel = 'EMAIL',
 * scoped to the product</li>
 * <li>club-scoped ("3"): notification_templates rows with channel = 'EMAIL',
 * scoped to club_id + product_id and to the given product modules</li>
 * </ul>
 * For layout "3", {@code modules} is the set of modules the caller may see; an
 * empty set matches nothing. It is ignored for the older layouts.
 */
@Component
public class EmailTemplateRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<EmailTemplateDto> ROW_MAPPER = (rs, rowNum) -> new EmailTemplateDto(
            UUID.fromString(rs.getString("id")),
            rs.getString("module_code"),
            rs.getString("template_key"),
            rs.getString("name"),
            rs.getString("subject"),
            rs.getString("body"),
            rs.getObject("created_at", Timestamp.class) != null
                    ? rs.getObject("created_at", Timestamp.class).toInstant().atOffset(java.time.ZoneOffset.UTC)
                    : null,
            rs.getObject("updated_at", Timestamp.class) != null
                    ? rs.getObject("updated_at", Timestamp.class).toInstant().atOffset(java.time.ZoneOffset.UTC)
                    : null
    );

    private static final String COLUMNS = "id, name, subject, body, created_at, updated_at";
    private static final String MODULE_COLUMNS = ", module_code, template_key";
    private static final String NO_MODULE_COLUMNS = ", NULL AS module_code, NULL AS template_key";

    public EmailTemplateRepository(JdbcTemplate platformJdbcTemplate) {
        this.jdbcTemplate = platformJdbcTemplate;
    }

    /** Table, selected columns and the row filter (and its arguments) every statement applies. */
    private record Scope(String table, String columns, String filter, List<Object> args) {
        Object[] argsWith(Object... leading) {
            List<Object> all = new ArrayList<>(List.of(leading));
            all.addAll(args);
            return all.toArray();
        }
    }

    private static Scope scope(TenantDatabaseDto tenant, UUID clubId, UUID productId, List<String> modules) {
        if (tenant.clubScoped()) {
            List<Object> args = new ArrayList<>(List.of(clubId, productId));
            args.addAll(modules);
            // An empty module set must match nothing; "IN ()" is not valid SQL.
            String moduleFilter = modules.isEmpty() ? " AND false"
                    : " AND module_code IN (" + String.join(", ", Collections.nCopies(modules.size(), "?")) + ")";
            return new Scope(table(tenant.schemaName(), "notification_templates"), COLUMNS + MODULE_COLUMNS,
                    " AND club_id = ? AND product_id = ?" + moduleFilter + " AND channel = 'EMAIL'", args);
        }
        if (tenant.usesNotificationsLayout()) {
            return new Scope(table(tenant.schemaName(), "notification_templates"), COLUMNS + NO_MODULE_COLUMNS,
                    " AND channel = 'EMAIL' AND product_id = ?", List.of(productId));
        }
        return new Scope(table(tenant.schemaName(), "email_templates"), COLUMNS + NO_MODULE_COLUMNS, "", List.of());
    }

    public List<EmailTemplateDto> findAll(TenantDatabaseDto tenant, UUID clubId, UUID productId,
            List<String> modules) {
        Scope s = scope(tenant, clubId, productId, modules);
        return jdbcTemplate.query(
                "SELECT " + s.columns() + " FROM " + s.table() + " WHERE true" + s.filter()
                        + " ORDER BY created_at DESC",
                ROW_MAPPER, s.argsWith()
        );
    }

    public Optional<EmailTemplateDto> findById(TenantDatabaseDto tenant, UUID clubId, UUID productId,
            List<String> modules, UUID id) {
        Scope s = scope(tenant, clubId, productId, modules);
        List<EmailTemplateDto> results = jdbcTemplate.query(
                "SELECT " + s.columns() + " FROM " + s.table() + " WHERE id = ?" + s.filter(),
                ROW_MAPPER, s.argsWith(id)
        );
        return results.stream().findFirst();
    }

    /** Layout "3" only: the club's template for a module template type (e.g. MEMBERSHIP / WELCOME). */
    public Optional<EmailTemplateDto> findByKey(TenantDatabaseDto tenant, UUID clubId, UUID productId,
            String moduleCode, String templateKey) {
        Scope s = scope(tenant, clubId, productId, List.of(moduleCode));
        List<EmailTemplateDto> results = jdbcTemplate.query(
                "SELECT " + s.columns() + " FROM " + s.table() + " WHERE template_key = ?" + s.filter()
                        + " AND status = 'ACTIVE'",
                ROW_MAPPER, s.argsWith(templateKey)
        );
        return results.stream().findFirst();
    }

    /** moduleCode / templateKey are stored for layout "3" only (the caller enforces that). */
    public UUID create(TenantDatabaseDto tenant, UUID clubId, UUID productId, String moduleCode,
            String templateKey, String name, String subject, String body) {
        UUID id = UUID.randomUUID();
        if (tenant.clubScoped()) {
            jdbcTemplate.update(
                    "INSERT INTO " + table(tenant.schemaName(), "notification_templates")
                            + " (id, club_id, product_id, module_code, template_key, channel, name, subject, body,"
                            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'EMAIL', ?, ?, ?, now(), now())",
                    id, clubId, productId, moduleCode, templateKey, name, subject, body
            );
        } else if (tenant.usesNotificationsLayout()) {
            jdbcTemplate.update(
                    "INSERT INTO " + table(tenant.schemaName(), "notification_templates")
                            + " (id, product_id, channel, name, subject, body, created_at, updated_at)"
                            + " VALUES (?, ?, 'EMAIL', ?, ?, ?, now(), now())",
                    id, productId, name, subject, body
            );
        } else {
            jdbcTemplate.update(
                    "INSERT INTO " + table(tenant.schemaName(), "email_templates")
                            + " (id, name, subject, body, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                    id, name, subject, body
            );
        }
        return id;
    }

    public boolean update(TenantDatabaseDto tenant, UUID clubId, UUID productId, List<String> modules, UUID id,
            String name, String subject, String body) {
        Scope s = scope(tenant, clubId, productId, modules);
        int rows = jdbcTemplate.update(
                "UPDATE " + s.table() + " SET name = ?, subject = ?, body = ?, updated_at = now() WHERE id = ?"
                        + s.filter(),
                s.argsWith(name, subject, body, id)
        );
        return rows > 0;
    }

    public boolean delete(TenantDatabaseDto tenant, UUID clubId, UUID productId, List<String> modules, UUID id) {
        Scope s = scope(tenant, clubId, productId, modules);
        int rows = jdbcTemplate.update("DELETE FROM " + s.table() + " WHERE id = ?" + s.filter(), s.argsWith(id));
        return rows > 0;
    }
}
