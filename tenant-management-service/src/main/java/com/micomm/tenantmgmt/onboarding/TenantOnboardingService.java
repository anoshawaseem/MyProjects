package com.micomm.tenantmgmt.onboarding;

import com.micomm.tenantmgmt.club.Club;
import com.micomm.tenantmgmt.club.ClubRepository;
import com.micomm.tenantmgmt.product.ClubProduct;
import com.micomm.tenantmgmt.product.ClubProductRepository;
import com.micomm.tenantmgmt.product.Product;
import com.micomm.tenantmgmt.product.ProductRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class TenantOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(TenantOnboardingService.class);
    private static final Pattern VALID_SCHEMA_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,62}$");

    private final ClubRepository clubRepository;
    private final ClubProductRepository clubProductRepository;
    private final ProductRepository productRepository;
    private final TenantDatabaseRepository tenantDatabaseRepository;
    private final TenantProvisioningProperties provisioningProperties;

    public TenantOnboardingService(ClubRepository clubRepository,
            ClubProductRepository clubProductRepository,
            ProductRepository productRepository,
            TenantDatabaseRepository tenantDatabaseRepository,
            TenantProvisioningProperties provisioningProperties) {
        this.clubRepository = clubRepository;
        this.clubProductRepository = clubProductRepository;
        this.productRepository = productRepository;
        this.tenantDatabaseRepository = tenantDatabaseRepository;
        this.provisioningProperties = provisioningProperties;
    }

    @Transactional
    public List<TenantDatabase> onboard(UUID clubId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new TenantOnboardingException(
                        "Club not found: " + clubId, clubId));

        List<ClubProduct> selectedProducts = clubProductRepository.findByClubId(clubId);

        if (selectedProducts.isEmpty()) {
            throw new TenantOnboardingException(
                    "No products selected for club " + clubId + " — select at least one product before onboarding",
                    clubId);
        }

        List<TenantDatabase> results = provisionSchemasForProducts(club, selectedProducts);

        club.setStatus("ACTIVE");
        clubRepository.save(club);

        return results;
    }

    /**
     * Provisions (creates if missing) tenant schemas for the given club/product
     * pairs.
     * Safe to call repeatedly — skips schemas that already exist.
     */
    @Transactional
    public List<TenantDatabase> provisionSchemasForProducts(Club club, List<ClubProduct> clubProducts) {
        List<TenantDatabase> results = new ArrayList<>();

        // One schema per CLUB (shared across all its products).
        String schemaName = toSchemaName(club.getSlug());
        validateSchemaName(schemaName);

        try {
            // A brand-new schema gets the current layout; an existing club schema (e.g. when a
            // product is added later) keeps the layout it already has, so its data is untouched.
            boolean newSchema = createSchemaIfNotExists(schemaName);
            TenantSchemaLayout layout = newSchema ? TenantSchemaLayout.FOR_NEW_SCHEMAS : detectLayout(schemaName);
            createTenantTables(schemaName, layout, club.getId());

            for (ClubProduct clubProduct : clubProducts) {
                Product product = productRepository.findById(clubProduct.getProductId())
                        .orElseThrow(() -> new TenantOnboardingException(
                                "Product not found: " + clubProduct.getProductId(), club.getId()));

                TenantDatabase tenantDatabase = tenantDatabaseRepository
                        .findByClubIdAndProductId(club.getId(), product.getId())
                        .orElseGet(TenantDatabase::new);

                tenantDatabase.setClubId(club.getId());
                tenantDatabase.setProductId(product.getId());
                tenantDatabase.setDatabaseIdentifier(schemaName);
                tenantDatabase.setDatabaseHostReference(
                        provisioningProperties.getHost() + ":" + provisioningProperties.getPort());
                tenantDatabase.setDatabaseSecretReference("shared-platform-credentials");
                tenantDatabase.setStatus("ACTIVE");
                tenantDatabase.setSchemaVersion(layout.version());

                results.add(tenantDatabaseRepository.save(tenantDatabase));

                log.info("Tenant schema [{}] (layout {}) provisioned successfully for club [{}] / product [{}]",
                        schemaName, layout, club.getSlug(), product.getCode());
            }

            return results;

        } catch (TenantOnboardingException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to provision schema for club [{}]", club.getId(), ex);
            throw new TenantOnboardingException(
                    "Failed to provision schema for club " + club.getId(), club.getId(), ex);
        }
    }

    /** Re-applies the idempotent DDL of the schema's own layout; never switches a schema's layout. */
    public String applyPendingMigrations(String schemaName) {
        validateSchemaName(schemaName);
        TenantSchemaLayout layout = detectLayout(schemaName);
        createTenantTables(schemaName, layout,
                layout == TenantSchemaLayout.CLUB_SCOPED ? owningClub(schemaName) : null);
        return layout.version();
    }

    /**
     * A module's default template for one channel, as copied into a club schema. templateKey is
     * the template type's code; types are defined in code, so source_type_id is left empty.
     */
    public record ModuleTemplateSeed(String templateKey, String name, String channel, String subject,
            String body, int version) {
    }

    /**
     * Copies a module's default templates into a club-scoped schema. Templates the club already
     * has for the same module / channel / key are left as they are (so club edits survive a
     * module being disabled and re-enabled). Returns the number of templates created.
     */
    public int seedModuleTemplates(String schemaName, UUID clubId, UUID productId, String moduleCode,
            List<ModuleTemplateSeed> seeds) {
        int[] created = { 0 };
        inSchema(schemaName, jdbcTemplate -> {
            if (TenantSchemaLayout.detect(jdbcTemplate) != TenantSchemaLayout.CLUB_SCOPED) {
                throw new IllegalStateException("Schema [" + schemaName + "] does not support modules");
            }
            for (ModuleTemplateSeed seed : seeds) {
                created[0] += jdbcTemplate.update("""
                        INSERT INTO notification_templates (club_id, product_id, module_code, template_key, channel,
                            name, subject, body, source_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (club_id, product_id, module_code, channel, template_key)
                            WHERE template_key IS NOT NULL DO NOTHING
                        """, clubId, productId, moduleCode, seed.templateKey(), seed.channel(), seed.name(),
                        seed.subject(), seed.body(), seed.version());
            }
        });
        log.info("Seeded {} of {} [{}] module templates into schema [{}] for product [{}]", created[0],
                seeds.size(), moduleCode, schemaName, productId);
        return created[0];
    }

    /** The single club a schema belongs to, per the tenant registry. */
    private UUID owningClub(String schemaName) {
        List<UUID> clubs = tenantDatabaseRepository.findAllByDatabaseIdentifier(schemaName).stream()
                .map(TenantDatabase::getClubId)
                .distinct()
                .toList();
        if (clubs.size() != 1) {
            throw new IllegalStateException("Schema [" + schemaName + "] must belong to exactly one club, found "
                    + clubs.size());
        }
        return clubs.get(0);
    }

    private TenantSchemaLayout detectLayout(String schemaName) {
        TenantSchemaLayout[] layout = { TenantSchemaLayout.LEGACY };
        inSchema(schemaName, jdbcTemplate -> layout[0] = TenantSchemaLayout.detect(jdbcTemplate));
        return layout[0];
    }

    public enum PartitionMaintenanceResult { MAINTAINED, SCHEMA_MISSING, NOT_PARTITIONED }

    /**
     * Creates the next {@code monthsAhead} monthly partitions and, when
     * {@code retentionMonths > 0}, detaches and drops partitions older than
     * that. Does nothing for missing schemas or schemas apply-all has not
     * converted to partitioned tables yet.
     */
    public PartitionMaintenanceResult maintainPartitions(String schemaName, int monthsAhead, int retentionMonths) {
        validateSchemaName(schemaName);
        if (!schemaExists(schemaName)) {
            return PartitionMaintenanceResult.SCHEMA_MISSING;
        }
        PartitionMaintenanceResult[] result = { PartitionMaintenanceResult.MAINTAINED };
        inSchema(schemaName, jdbcTemplate -> {
            Boolean converted = jdbcTemplate.queryForObject(
                    "SELECT to_regprocedure('ensure_monthly_partitions(text,date,date)') IS NOT NULL", Boolean.class);
            if (!Boolean.TRUE.equals(converted)) {
                result[0] = PartitionMaintenanceResult.NOT_PARTITIONED;
                return;
            }
            List<String> tables = TenantSchemaLayout.detect(jdbcTemplate).partitionedTablesDropOrder();
            for (String table : tables) {
                Integer created = jdbcTemplate.queryForObject(
                        TenantSchemaDefinition.ensurePartitionsStatement(table, monthsAhead), Integer.class);
                if (created != null && created > 0) {
                    log.info("Created {} partition(s) for [{}].{}", created, schemaName, table);
                }
            }
            if (retentionMonths > 0) {
                // Children before parents so foreign-keyed partitions detach cleanly.
                for (String table : tables) {
                    Integer dropped = jdbcTemplate.queryForObject(
                            TenantSchemaDefinition.dropPartitionsStatement(table, retentionMonths), Integer.class);
                    if (dropped != null && dropped > 0) {
                        log.info("Dropped {} expired partition(s) from [{}].{}", dropped, schemaName, table);
                    }
                }
            }
        });
        return result[0];
    }

    private boolean schemaExists(String schemaName) {
        try (HikariDataSource dataSource = buildSharedDataSource()) {
            Integer count = new JdbcTemplate(dataSource).queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, schemaName);
            return count != null && count > 0;
        }
    }

    private String toSchemaName(String clubSlug) {
        String cleanSlug = clubSlug.replace("-", "_").toLowerCase();
        return "tenant_" + cleanSlug;
    }

    private void validateSchemaName(String schemaName) {
        if (!VALID_SCHEMA_NAME.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Invalid schema name derived: " + schemaName);
        }
    }

    /** Returns true if the schema was created by this call, false if it already existed. */
    private boolean createSchemaIfNotExists(String schemaName) {
        try (HikariDataSource dataSource = buildSharedDataSource()) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, schemaName);

            if (count != null && count > 0) {
                log.info("Schema [{}] already exists — skipping CREATE SCHEMA", schemaName);
                return false;
            }

            jdbcTemplate.execute("CREATE SCHEMA \"" + schemaName + "\"");
            log.info("Created schema [{}]", schemaName);
            return true;
        }
    }

    private void createTenantTables(String schemaName, TenantSchemaLayout layout, UUID clubId) {
        List<String> statements = layout.statements(clubId);
        inSchema(schemaName, jdbcTemplate -> {
            for (String statement : statements) {
                jdbcTemplate.execute(statement);
            }
        });
        log.info("Applied {} schema statements (layout {}) in schema [{}]", statements.size(), layout, schemaName);
    }

    /**
     * Runs work against a single connection whose search_path is set to the
     * tenant schema. A plain JdbcTemplate over a pool may hand each call a
     * different connection, so SET search_path would not reliably apply.
     */
    private void inSchema(String schemaName, Consumer<JdbcTemplate> work) {
        validateSchemaName(schemaName);
        try (HikariDataSource dataSource = buildSharedDataSource()) {
            new JdbcTemplate(dataSource).execute((ConnectionCallback<Void>) connection -> {
                JdbcTemplate scoped = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                scoped.execute("SET search_path TO \"" + schemaName + "\"");
                work.accept(scoped);
                return null;
            });
        }
    }

    private HikariDataSource buildSharedDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(provisioningProperties.jdbcUrl());
        config.setUsername(provisioningProperties.getAdminUsername());
        config.setPassword(provisioningProperties.getAdminPassword());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10000);
        config.setPoolName("provisioning-shared");
        return new HikariDataSource(config);
    }

    /**
     * Removes a product's data/tracking from a club's shared tenant schema.
     * Since the schema is shared across all of a club's products, this only
     * deletes rows scoped to this product_id — it does NOT drop the schema,
     * unless this was the club's last remaining product.
     */
    @Transactional
    public void dropSchemaForProduct(UUID clubId, UUID productId) {
        TenantDatabase tenantDatabase = tenantDatabaseRepository
                .findByClubIdAndProductId(clubId, productId)
                .orElse(null);

        if (tenantDatabase == null) {
            log.info("No tenant schema found for club [{}] / product [{}] — nothing to drop", clubId, productId);
            return;
        }

        String schemaName = tenantDatabase.getDatabaseIdentifier();
        validateSchemaName(schemaName);

        long remainingProductsForSchema = tenantDatabaseRepository
                .findAllByDatabaseIdentifier(schemaName)
                .stream()
                .filter(td -> !td.getProductId().equals(productId))
                .count();

        inSchema(schemaName, jdbcTemplate -> {
            if (remainingProductsForSchema == 0) {
                // Last product for this club — safe to drop the entire schema.
                jdbcTemplate.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
                log.info("Dropped schema [{}] (last product removed) for club [{}] / product [{}]", schemaName, clubId,
                        productId);
            } else {
                // Other products still use this schema — only purge this product's rows.
                purgeProductData(jdbcTemplate, productId);
                log.info("Purged product [{}] data from shared schema [{}] for club [{}] (schema retained)",
                        productId, schemaName, clubId);
            }
        });

        tenantDatabaseRepository.delete(tenantDatabase);
    }

    private void purgeProductData(JdbcTemplate jdbcTemplate, UUID productId) {
        if (TenantSchemaLayout.detect(jdbcTemplate) != TenantSchemaLayout.LEGACY) {
            // notification_recipients go with their notification (ON DELETE CASCADE).
            jdbcTemplate.update("DELETE FROM notifications WHERE product_id = ?", productId);
            jdbcTemplate.update("DELETE FROM notification_templates WHERE product_id = ?", productId);
            jdbcTemplate.update("DELETE FROM usage_counters WHERE product_id = ?", productId);
            // Club schemas created since campaigns was dropped from the layout don't have it.
            deleteIfTableExists(jdbcTemplate, "campaigns", productId);
            // audit_logs is kept: the club's audit trail outlives a product being removed.
            return;
        }
        // Legacy layout. Delete in FK-dependency order: child tables referencing *_id before parents.
        jdbcTemplate.update(
                "DELETE FROM email_log_recipients WHERE email_log_id IN (SELECT id FROM email_logs WHERE product_id = ?)",
                productId);
        jdbcTemplate.update("DELETE FROM email_logs WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM email_templates WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM sms_logs WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM sms_templates WHERE product_id = ?", productId);
        jdbcTemplate.update(
                "DELETE FROM campaign_recipients WHERE campaign_id IN (SELECT id FROM campaigns WHERE product_id = ?)",
                productId);
        jdbcTemplate.update("DELETE FROM campaigns WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM usage_counters WHERE product_id = ?", productId);
        // audit_logs is kept: the club's audit trail outlives a product being removed.
    }

    /** {@code table} is one of the fixed names above, never user input. */
    private static void deleteIfTableExists(JdbcTemplate jdbcTemplate, String table, UUID productId) {
        Boolean exists = jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
        if (Boolean.TRUE.equals(exists)) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE product_id = ?", productId);
        }
    }
}