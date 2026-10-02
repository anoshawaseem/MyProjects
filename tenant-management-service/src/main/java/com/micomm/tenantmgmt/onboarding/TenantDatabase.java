package com.micomm.tenantmgmt.onboarding;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenant_databases")
public class TenantDatabase {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "club_id", nullable = false)
    private UUID clubId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "database_identifier", nullable = false, unique = true)
    private String databaseIdentifier;

    @Column(name = "database_host_reference", nullable = false)
    private String databaseHostReference;

    @Column(name = "database_secret_reference", nullable = false)
    private String databaseSecretReference;

    @Column(nullable = false)
    private String status = "PROVISIONING";

    @Column(name = "schema_version")
    private String schemaVersion;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public UUID getClubId() { return clubId; }
    public void setClubId(UUID clubId) { this.clubId = clubId; }
    public UUID getProductId() { return productId; }
    public void setProductId(UUID productId) { this.productId = productId; }
    public String getDatabaseIdentifier() { return databaseIdentifier; }
    public void setDatabaseIdentifier(String databaseIdentifier) { this.databaseIdentifier = databaseIdentifier; }
    public String getDatabaseHostReference() { return databaseHostReference; }
    public void setDatabaseHostReference(String databaseHostReference) { this.databaseHostReference = databaseHostReference; }
    public String getDatabaseSecretReference() { return databaseSecretReference; }
    public void setDatabaseSecretReference(String databaseSecretReference) { this.databaseSecretReference = databaseSecretReference; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
}