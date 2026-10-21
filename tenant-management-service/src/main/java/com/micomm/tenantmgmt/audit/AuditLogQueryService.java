package com.micomm.tenantmgmt.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.micomm.tenantmgmt.onboarding.TenantDatabase;
import com.micomm.tenantmgmt.onboarding.TenantSchemaLayout;

/** Reads a club's audit log from its schema, newest first, keyset-paged on (created_at, id). */
@Service
public class AuditLogQueryService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    private static final Pattern VALID_SCHEMA_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,62}$");

    /** actor is the readable name stored with the entry; details holds actorType, actorId, ip and the event's facts. */
    public record AuditLogEntryDto(UUID id, OffsetDateTime createdAt, UUID productId, String actor, String actorType,
            String action, String entityType, String entityId, JsonNode details) {
    }

    /** Pass nextCursor back as cursor for the next page; null means there are no more entries. */
    public record AuditLogPageDto(List<AuditLogEntryDto> items, String nextCursor) {
    }

    private final ClubAuditLog clubAuditLog;
    private final PlatformDatabase platformDatabase;
    private final ObjectMapper objectMapper;

    public AuditLogQueryService(ClubAuditLog clubAuditLog, PlatformDatabase platformDatabase,
            ObjectMapper objectMapper) {
        this.clubAuditLog = clubAuditLog;
        this.platformDatabase = platformDatabase;
        this.objectMapper = objectMapper;
    }

    public AuditLogPageDto list(UUID clubId, UUID productId, String action, OffsetDateTime from, OffsetDateTime to,
            String cursor, Integer limit) {
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        Optional<TenantDatabase> schema = clubAuditLog.clubSchema(clubId);
        if (schema.isEmpty()) {
            // Not onboarded yet: nothing has been recorded.
            return new AuditLogPageDto(List.of(), null);
        }
        String schemaName = schema.get().getDatabaseIdentifier();
        if (!VALID_SCHEMA_NAME.matcher(schemaName).matches()) {
            throw new IllegalStateException("Invalid schema name: " + schemaName);
        }
        Boolean hasTable = platformDatabase.jdbc().queryForObject("SELECT to_regclass(?) IS NOT NULL",
                Boolean.class, "\"" + schemaName + "\".audit_logs");
        if (!Boolean.TRUE.equals(hasTable)) {
            return new AuditLogPageDto(List.of(), null);
        }

        StringBuilder sql = new StringBuilder("SELECT id, created_at, product_id, actor_email, action, entity_type,"
                + " entity_id, details FROM \"" + schemaName + "\".audit_logs WHERE true");
        List<Object> args = new ArrayList<>();
        if (TenantSchemaLayout.CLUB_SCOPED.version().equals(schema.get().getSchemaVersion())) {
            sql.append(" AND club_id = ?");
            args.add(clubId);
        }
        if (productId != null) {
            sql.append(" AND product_id = ?");
            args.add(productId);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            args.add(action.trim().toUpperCase());
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND created_at < ?");
            args.add(to);
        }
        if (cursor != null && !cursor.isBlank()) {
            Cursor c = Cursor.decode(cursor);
            sql.append(" AND (created_at, id) < (?, ?)");
            args.add(c.createdAt());
            args.add(c.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(pageSize + 1);

        List<AuditLogEntryDto> rows = platformDatabase.jdbc().query(sql.toString(), (rs, n) -> {
            JsonNode details = parseDetails(rs.getString("details"));
            return new AuditLogEntryDto(
                    rs.getObject("id", UUID.class),
                    rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC),
                    rs.getObject("product_id", UUID.class),
                    rs.getString("actor_email"),
                    details.path("actorType").asText(null),
                    rs.getString("action"),
                    rs.getString("entity_type"),
                    rs.getString("entity_id"),
                    details);
        }, args.toArray());

        if (rows.size() <= pageSize) {
            return new AuditLogPageDto(rows, null);
        }
        List<AuditLogEntryDto> page = rows.subList(0, pageSize);
        AuditLogEntryDto last = page.get(page.size() - 1);
        return new AuditLogPageDto(List.copyOf(page), new Cursor(last.createdAt(), last.id()).encode());
    }

    /** Entries written before details were JSON are returned as a plain string. */
    private JsonNode parseDetails(String details) {
        if (details == null || details.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(details);
        } catch (Exception ex) {
            return TextNode.valueOf(details);
        }
    }

    private record Cursor(OffsetDateTime createdAt, UUID id) {

        String encode() {
            String raw = createdAt.toInstant() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                return new Cursor(Instant.parse(parts[0]).atOffset(ZoneOffset.UTC), UUID.fromString(parts[1]));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Invalid cursor");
            }
        }
    }
}
