package com.micomm.tenantmgmt.module;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.micomm.tenantmgmt.module.ModuleDtos.ClubModuleDto;
import com.micomm.tenantmgmt.module.ModuleDtos.ModuleTemplateTypeDto;
import com.micomm.tenantmgmt.module.ModuleDtos.ProductModuleDto;
import com.micomm.tenantmgmt.module.ModuleDtos.TemplateDefault;
import com.micomm.tenantmgmt.onboarding.TenantOnboardingService.ModuleTemplateSeed;

/**
 * Control-database access for product_modules, club_product_modules and module_template_defaults.
 * Template types themselves are the {@link TemplateType} enum.
 */
@Repository
public class ModuleRepository {

    private static final RowMapper<ProductModuleDto> MODULE = (rs, n) -> new ProductModuleDto(
            rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("code"),
            rs.getString("name"), rs.getString("description"), rs.getString("status"), rs.getInt("sort_order"));

    private static final String MODULE_COLUMNS = "id, product_id, code, name, description, status, sort_order";

    private final JdbcTemplate jdbcTemplate;

    public ModuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ProductModuleDto> findModules(UUID productId) {
        return jdbcTemplate.query("SELECT " + MODULE_COLUMNS + " FROM product_modules WHERE product_id = ?"
                + " ORDER BY sort_order, code", MODULE, productId);
    }

    public Optional<ProductModuleDto> findModule(UUID productId, String code) {
        return jdbcTemplate.query("SELECT " + MODULE_COLUMNS + " FROM product_modules"
                + " WHERE product_id = ? AND code = ?", MODULE, productId, code).stream().findFirst();
    }

    public ProductModuleDto insertModule(UUID productId, String code, String name, String description,
            int sortOrder) {
        return jdbcTemplate.queryForObject("INSERT INTO product_modules (product_id, code, name, description,"
                + " sort_order) VALUES (?, ?, ?, ?, ?) RETURNING " + MODULE_COLUMNS,
                MODULE, productId, code, name, description, sortOrder);
    }

    /** The template types a module has defaults for, each with those defaults. */
    public List<ModuleTemplateTypeDto> findModuleTemplateTypes(UUID moduleId, String moduleCode) {
        Map<String, List<TemplateDefault>> defaults = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT template_type, channel, subject, body, version
                FROM module_template_defaults
                WHERE module_id = ? ORDER BY channel
                """, rs -> {
            defaults.computeIfAbsent(rs.getString("template_type"), k -> new ArrayList<>())
                    .add(new TemplateDefault(rs.getString("channel"), rs.getString("subject"), rs.getString("body"),
                            rs.getInt("version")));
        }, moduleId);

        return Arrays.stream(TemplateType.values())
                .filter(t -> defaults.containsKey(t.name()))
                .map(t -> new ModuleTemplateTypeDto(moduleCode, t.name(), t.displayName(), t.description(),
                        t.variables(), defaults.get(t.name())))
                .toList();
    }

    public void insertTemplateDefault(UUID moduleId, String templateType, TemplateDefault d) {
        jdbcTemplate.update("INSERT INTO module_template_defaults (module_id, template_type, channel, subject, body,"
                + " version) VALUES (?, ?, ?, ?, ?, ?)", moduleId, templateType, d.channel(), d.subject(), d.body(),
                d.version() != null ? d.version() : 1);
    }

    /**
     * A module's defaults, ready to copy into a club schema. Rows whose type is not (or no
     * longer) a {@link TemplateType} are skipped.
     */
    public List<ModuleTemplateSeed> findSeeds(UUID moduleId) {
        List<Object> args = new ArrayList<>(List.of(moduleId));
        Arrays.stream(TemplateType.values()).map(TemplateType::name).forEach(args::add);
        return jdbcTemplate.query("SELECT template_type, channel, subject, body, version"
                + " FROM module_template_defaults WHERE module_id = ? AND template_type IN ("
                + String.join(", ", Collections.nCopies(TemplateType.values().length, "?")) + ")"
                + " ORDER BY template_type, channel", (rs, n) -> {
            TemplateType type = TemplateType.valueOf(rs.getString("template_type"));
            return new ModuleTemplateSeed(type.name(), type.displayName(), rs.getString("channel"),
                    rs.getString("subject"), rs.getString("body"), rs.getInt("version"));
        }, args.toArray());
    }

    /** All of the product's modules, with whether this club has each one enabled. */
    public List<ClubModuleDto> findClubModules(UUID clubProductId, UUID productId) {
        return jdbcTemplate.query("""
                SELECT m.code, m.name, m.description, COALESCE(cpm.enabled, false) AND m.status = 'ACTIVE' AS enabled
                FROM product_modules m
                LEFT JOIN club_product_modules cpm ON cpm.module_id = m.id AND cpm.club_product_id = ?
                WHERE m.product_id = ?
                ORDER BY m.sort_order, m.code
                """, (rs, n) -> new ClubModuleDto(rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getBoolean("enabled")), clubProductId, productId);
    }

    /** Codes of the ACTIVE modules a club has enabled for a product (what the services enforce). */
    public List<String> findEnabledModuleCodes(UUID clubId, UUID productId) {
        return jdbcTemplate.queryForList("""
                SELECT m.code
                FROM club_products cp
                JOIN club_product_modules cpm ON cpm.club_product_id = cp.id AND cpm.enabled
                JOIN product_modules m ON m.id = cpm.module_id AND m.status = 'ACTIVE'
                WHERE cp.club_id = ? AND cp.product_id = ?
                ORDER BY m.code
                """, String.class, clubId, productId);
    }

    /** Sets one module's flag; returns true if the module was not enabled before this call. */
    public boolean setEnabled(UUID clubProductId, UUID moduleId, boolean enabled) {
        List<Boolean> before = jdbcTemplate.queryForList("SELECT enabled FROM club_product_modules"
                + " WHERE club_product_id = ? AND module_id = ? FOR UPDATE", Boolean.class, clubProductId, moduleId);
        jdbcTemplate.update("""
                INSERT INTO club_product_modules (club_product_id, module_id, enabled) VALUES (?, ?, ?)
                ON CONFLICT (club_product_id, module_id)
                DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()
                """, clubProductId, moduleId, enabled);
        return enabled && (before.isEmpty() || !before.get(0));
    }
}
