package com.micomm.email.client.dto;

import java.util.List;

/**
 * schemaVersion is the tenant schema's table layout:
 * <ul>
 * <li>"1" - per-channel tables (email_logs, email_log_recipients, email_templates)</li>
 * <li>"2" - notifications / notification_recipients / notification_templates,
 * scoped by product_id</li>
 * <li>"3" - the same notifications tables with club_id and product_id on every
 * table; every statement must set and filter both (clubs created since).
 * Templates belong to a product module, and enabledModules lists the modules
 * this club has switched on for the product.</li>
 * </ul>
 * clubName / productName fill the {{clubName}} / {{productName}} merge fields of sends.
 */
public record TenantDatabaseDto(String schemaName, String schemaVersion, List<String> enabledModules,
        String clubName, String productName) {

    public static final String NOTIFICATIONS_LAYOUT = "2";
    public static final String CLUB_SCOPED_LAYOUT = "3";

    public TenantDatabaseDto {
        enabledModules = enabledModules != null ? List.copyOf(enabledModules) : List.of();
    }

    /** True for layouts "2" and "3" (notifications tables). */
    public boolean usesNotificationsLayout() {
        return NOTIFICATIONS_LAYOUT.equals(schemaVersion) || CLUB_SCOPED_LAYOUT.equals(schemaVersion);
    }

    /** True for layout "3": tables carry club_id, which statements must set and filter on. */
    public boolean clubScoped() {
        return CLUB_SCOPED_LAYOUT.equals(schemaVersion);
    }

    /** Modules exist only in layout "3"; older schemas never support them. */
    public boolean supportsModules() {
        return clubScoped();
    }

    public boolean moduleEnabled(String moduleCode) {
        return enabledModules.contains(moduleCode);
    }
}
