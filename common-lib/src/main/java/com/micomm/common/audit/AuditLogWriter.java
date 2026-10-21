package com.micomm.common.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.security.MicommActor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Writes audit entries into a club's own schema (its audit_logs table), so each club's log lives
 * and is deleted with the rest of its data. Every layout's audit_logs has the same columns, plus
 * club_id in club-scoped ("3") schemas:
 * (id, [club_id,] product_id, actor_email, action, entity_type, entity_id, details, created_at).
 * actor_email holds the actor's readable label; details holds JSON with the actor's type and id,
 * the caller's IP address and the event's own details.
 *
 * Auditing never breaks the action it records: a failed write is logged as an error and dropped.
 * Uses schema-qualified table names (not SET search_path), so any pooled JdbcTemplate on the
 * platform database works.
 */
public class AuditLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditLogWriter.class);
    private static final Pattern VALID_SCHEMA_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,62}$");

    private final JdbcTemplate platformJdbc;
    private final ObjectMapper objectMapper;
    // Schemas known to have audit_logs; schemas without it are re-checked, so a later
    // "apply pending migrations" that adds the table is picked up.
    private final Set<String> schemasWithTable = ConcurrentHashMap.newKeySet();

    public AuditLogWriter(JdbcTemplate platformJdbc, ObjectMapper objectMapper) {
        this.platformJdbc = platformJdbc;
        this.objectMapper = objectMapper;
    }

    /** @param clubScoped true for layout "3" schemas, whose audit_logs has a club_id column */
    public void write(String schemaName, boolean clubScoped, AuditEvent event) {
        try {
            if (schemaName == null || !VALID_SCHEMA_NAME.matcher(schemaName).matches()) {
                throw new IllegalArgumentException("Invalid schema name: " + schemaName);
            }
            if (!hasAuditTable(schemaName)) {
                log.warn("Schema [{}] has no audit_logs table; not recorded: {} for club {}", schemaName,
                        event.action(), event.clubId());
                return;
            }
            MicommActor actor = event.actor() != null ? event.actor() : MicommActor.current();

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("actorType", actor.type().name());
            if (actor.id() != null) {
                details.put("actorId", actor.id().toString());
            }
            String ip = currentClientIp();
            if (ip != null) {
                details.put("ip", ip);
            }
            details.putAll(event.details());

            List<Object> args = new ArrayList<>();
            if (clubScoped) {
                args.add(event.clubId());
            }
            args.add(event.productId());
            args.add(cut(actor.label(), 320));
            args.add(cut(event.action(), 255));
            args.add(cut(event.entityType(), 100));
            args.add(cut(event.entityId(), 100));
            args.add(objectMapper.writeValueAsString(details));

            platformJdbc.update("INSERT INTO \"" + schemaName + "\".audit_logs ("
                    + (clubScoped ? "club_id, " : "")
                    + "product_id, actor_email, action, entity_type, entity_id, details, created_at) VALUES ("
                    + (clubScoped ? "?, " : "") + "?, ?, ?, ?, ?, ?, now())", args.toArray());
        } catch (JsonProcessingException | RuntimeException ex) {
            log.error("Could not write audit log {} for club {} to schema [{}]", event.action(), event.clubId(),
                    schemaName, ex);
        }
    }

    private boolean hasAuditTable(String schemaName) {
        if (schemasWithTable.contains(schemaName)) {
            return true;
        }
        Boolean exists = platformJdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                "\"" + schemaName + "\".audit_logs");
        if (Boolean.TRUE.equals(exists)) {
            schemasWithTable.add(schemaName);
            return true;
        }
        return false;
    }

    /** The caller's address, from the first X-Forwarded-For hop when behind a proxy. */
    private static String currentClientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return cut(forwarded.split(",")[0].trim(), 64);
        }
        return request.getRemoteAddr();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
