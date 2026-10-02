package com.micomm.tenantmgmt.provider;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "provider_accounts")
public class ProviderAccount {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "club_id", nullable = false)
    private UUID clubId;

    @Column(name = "provider_type_id", nullable = false)
    private UUID providerTypeId;

    private String name;

    @Column(name = "secret_reference", nullable = false)
    private String secretReference;

    @Column(name = "config_json")
    private String configJson;

    private String status = "ACTIVE";

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public UUID getClubId() { return clubId; }
    public void setClubId(UUID clubId) { this.clubId = clubId; }
    public UUID getProviderTypeId() { return providerTypeId; }
    public void setProviderTypeId(UUID providerTypeId) { this.providerTypeId = providerTypeId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSecretReference() { return secretReference; }
    public void setSecretReference(String secretReference) { this.secretReference = secretReference; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}