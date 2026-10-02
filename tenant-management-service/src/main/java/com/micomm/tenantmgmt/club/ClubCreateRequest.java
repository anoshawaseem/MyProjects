package com.micomm.tenantmgmt.club;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class ClubCreateRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String slug;

    private String country;

    private String timezone;

    private String contactName;

    @Email
    private String contactEmail;

    private String contactPhone;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
}