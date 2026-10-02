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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class TenantOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(TenantOnboardingService.class);
    private static final Pattern VALID_SCHEMA_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,62}$");
    private static final String SCHEMA_VERSION = "1";

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
                    "No products selected for club " + clubId + " — select at least one product before onboarding", clubId);
        }

        List<TenantDatabase> results = provisionSchemasForProducts(club, selectedProducts);

        club.setStatus("ACTIVE");
        clubRepository.save(club);

        return results;
    }

    /**
     * Provisions (creates if missing) tenant schemas for the given club/product pairs.
     * Safe to call repeatedly — skips schemas that already exist.
     */
    @Transactional
    public List<TenantDatabase> provisionSchemasForProducts(Club club, List<ClubProduct> clubProducts) {
        List<TenantDatabase> results = new ArrayList<>();

        try {
            for (ClubProduct clubProduct : clubProducts) {
                Product product = productRepository.findById(clubProduct.getProductId())
                        .orElseThrow(() -> new TenantOnboardingException(
                                "Product not found: " + clubProduct.getProductId(), club.getId()));

                String schemaName = toSchemaName(club.getSlug(), product.getCode());
                validateSchemaName(schemaName);

                createSchemaIfNotExists(schemaName);
                createTenantTables(schemaName);

                TenantDatabase tenantDatabase = tenantDatabaseRepository
                        .findByClubIdAndProductId(club.getId(), product.getId())
                        .orElseGet(TenantDatabase::new);

                tenantDatabase.setClubId(club.getId());
                tenantDatabase.setProductId(product.getId());
                tenantDatabase.setDatabaseIdentifier(schemaName);
                tenantDatabase.setDatabaseHostReference(provisioningProperties.getHost() + ":" + provisioningProperties.getPort());
                tenantDatabase.setDatabaseSecretReference("shared-platform-credentials");
                tenantDatabase.setStatus("ACTIVE");
                tenantDatabase.setSchemaVersion(SCHEMA_VERSION);

                results.add(tenantDatabaseRepository.save(tenantDatabase));

                log.info("Tenant schema [{}] provisioned successfully for club [{}] / product [{}]", schemaName, club.getSlug(), product.getCode());
            }

            return results;

        } catch (TenantOnboardingException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to provision schemas for club [{}]", club.getId(), ex);
            throw new TenantOnboardingException(
                    "Failed to provision schemas for club " + club.getId(), club.getId(), ex);
        }
    }

    public String applyPendingMigrations(String schemaName) {
        validateSchemaName(schemaName);
        createTenantTables(schemaName);
        return SCHEMA_VERSION;
    }

    private String toSchemaName(String clubSlug, String productCode) {
        String cleanSlug = clubSlug.replace("-", "_").toLowerCase();
        String cleanProduct = productCode.replace("-", "_").toLowerCase();
        return "tenant_" + cleanSlug + "_" + cleanProduct;
    }

    private void validateSchemaName(String schemaName) {
        if (!VALID_SCHEMA_NAME.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Invalid schema name derived: " + schemaName);
        }
    }

    private void createSchemaIfNotExists(String schemaName) {
        try (HikariDataSource dataSource = buildSharedDataSource()) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, schemaName);

            if (count != null && count > 0) {
                log.info("Schema [{}] already exists — skipping CREATE SCHEMA", schemaName);
                return;
            }

            jdbcTemplate.execute("CREATE SCHEMA \"" + schemaName + "\"");
            log.info("Created schema [{}]", schemaName);
        }
    }

    private void createTenantTables(String schemaName) {
        try (HikariDataSource dataSource = buildSharedDataSource()) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.execute("SET search_path TO \"" + schemaName + "\"");

            List<String> statements = TenantSchemaDefinition.createTableStatements();
            for (String statement : statements) {
                jdbcTemplate.execute(statement);
            }

            log.info("Created {} tables in schema [{}]", statements.size(), schemaName);
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
     * Drops the tenant schema for a specific club/product pair and removes its tracking row.
     * Destroys all data in that schema — caller must confirm with the user first.
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

        try (HikariDataSource dataSource = buildSharedDataSource()) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
            log.info("Dropped schema [{}] for club [{}] / product [{}]", schemaName, clubId, productId);
        }

        tenantDatabaseRepository.delete(tenantDatabase);
    }
}