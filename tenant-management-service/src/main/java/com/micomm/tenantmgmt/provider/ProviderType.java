package com.micomm.tenantmgmt.provider;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "provider_types")
public class ProviderType {

    @Id
    @GeneratedValue
    private UUID id;

    private String code;
    private String channel;
    private String name;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}