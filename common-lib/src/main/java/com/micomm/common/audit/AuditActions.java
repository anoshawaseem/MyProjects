package com.micomm.common.audit;

/** Action names stored in audit logs. Kept stable: existing log rows refer to them. */
public final class AuditActions {

    private AuditActions() {
    }

    // Access
    public static final String USER_LOGIN = "USER_LOGIN";
    public static final String USER_LOGIN_FAILED = "USER_LOGIN_FAILED";

    // Club setup (tenant-management-service)
    public static final String CLUB_ONBOARDED = "CLUB_ONBOARDED";
    public static final String CLUB_PRODUCTS_UPDATED = "CLUB_PRODUCTS_UPDATED";
    public static final String MODULES_UPDATED = "MODULES_UPDATED";
    public static final String PROVIDER_ACCOUNT_CREATED = "PROVIDER_ACCOUNT_CREATED";
    public static final String PROVIDER_ACCOUNT_DISABLED = "PROVIDER_ACCOUNT_DISABLED";
    public static final String PRODUCT_PROVIDER_ASSIGNED = "PRODUCT_PROVIDER_ASSIGNED";
    public static final String MODULE_PROVIDER_ASSIGNED = "MODULE_PROVIDER_ASSIGNED";
    public static final String MODULE_PROVIDER_CLEARED = "MODULE_PROVIDER_CLEARED";
    public static final String API_KEY_CREATED = "API_KEY_CREATED";
    public static final String API_KEY_REVOKED = "API_KEY_REVOKED";

    // Email (email-service)
    public static final String EMAIL_SEND_QUEUED = "EMAIL_SEND_QUEUED";
    public static final String TEMPLATE_CREATED = "TEMPLATE_CREATED";
    public static final String TEMPLATE_UPDATED = "TEMPLATE_UPDATED";
    public static final String TEMPLATE_DELETED = "TEMPLATE_DELETED";
    public static final String GLOBAL_TEMPLATE_CREATED = "GLOBAL_TEMPLATE_CREATED";
    public static final String GLOBAL_TEMPLATE_UPDATED = "GLOBAL_TEMPLATE_UPDATED";
    public static final String GLOBAL_TEMPLATE_DELETED = "GLOBAL_TEMPLATE_DELETED";
}
