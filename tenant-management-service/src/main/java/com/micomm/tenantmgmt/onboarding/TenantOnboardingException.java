package com.micomm.tenantmgmt.onboarding;

import java.util.UUID;

public class TenantOnboardingException extends RuntimeException {

    private final UUID clubId;

    public TenantOnboardingException(String message, UUID clubId) {
        super(message);
        this.clubId = clubId;
    }

    public TenantOnboardingException(String message, UUID clubId, Throwable cause) {
        super(message, cause);
        this.clubId = clubId;
    }

    public UUID getClubId() {
        return clubId;
    }
}