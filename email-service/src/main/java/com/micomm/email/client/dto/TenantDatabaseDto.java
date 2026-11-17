package com.micomm.email.client.dto;

import java.util.List;

/**
 * schemaVersion is the tenant schema's table layout:
 * <ul>
 * <li>"1" - per-channel tables (email_logs, email_log_recipients, email_templates)</li>
 * <li>"2" - notifications / notification_recipients / notification_templates,
 * scoped by product_id</li>
 * <li>"3" - the notifications tables plus product modules and global templates, with
 * club_id on every table; every statement must set and filter it</li>
 * <li>"4" - the same as "3" without club_id: the schema itself is the club</li>
 * <li>"5" - "4" with sends normalised: notifications (with type) → notification_deliveries
 * (one per channel) → notification_recipients</li>
 * <li>"6" - "5" with recipients reduced to what cannot be derived (no product_id or
 * created_at)</li>
 * <li>"7" - "6" plus notification_provider_events, the provider's webhook events per
 * recipient</li>
 * <li>"8" - "7" with template (template_id / global_template_id) and subject columns per
 * delivery; notifications has no template_id or recipient_count (clubs created since)</li>
 * </ul>
 * In "3" to "8" templates belong to a product module, and enabledModules lists the modules
 * this club has switched on for the product.
 * clubName / productName fill the {{clubName}} / {{productName}} merge fields of sends.
 */
public record TenantDatabaseDto(String schemaName, String schemaVersion, List<String> enabledModules,
        String clubName, String productName) {

    public static final String NOTIFICATIONS_LAYOUT = "2";
    public static final String CLUB_SCOPED_LAYOUT = "3";
    public static final String CLUB_SCHEMA_LAYOUT = "4";
    public static final String CHANNEL_DELIVERIES_LAYOUT = "5";
    public static final String LEAN_RECIPIENTS_LAYOUT = "6";
    public static final String PROVIDER_EVENTS_LAYOUT = "7";
    public static final String PER_CHANNEL_TEMPLATES_LAYOUT = "8";

    public TenantDatabaseDto {
        enabledModules = enabledModules != null ? List.copyOf(enabledModules) : List.of();
    }

    /** True for layouts "2" to "8" (notifications tables). */
    public boolean usesNotificationsLayout() {
        return NOTIFICATIONS_LAYOUT.equals(schemaVersion) || supportsModules();
    }

    /** True for layout "3" only: tables carry club_id, which statements must set and filter on. */
    public boolean hasClubIdColumn() {
        return CLUB_SCOPED_LAYOUT.equals(schemaVersion);
    }

    /**
     * Layouts "3" to "8": product modules (module_code / template_key columns) and global
     * templates. Older schemas never support them.
     */
    public boolean supportsModules() {
        return CLUB_SCOPED_LAYOUT.equals(schemaVersion) || CLUB_SCHEMA_LAYOUT.equals(schemaVersion)
                || usesDeliveries();
    }

    /**
     * Layouts "5" to "8": a send is a notifications row (with its type) plus one
     * notification_deliveries row per channel; recipients belong to a delivery. Older layouts
     * keep one row per channel directly in notifications.
     */
    public boolean usesDeliveries() {
        return CHANNEL_DELIVERIES_LAYOUT.equals(schemaVersion) || LEAN_RECIPIENTS_LAYOUT.equals(schemaVersion)
                || hasProviderEvents();
    }

    /** Layouts "7" and "8": notification_provider_events stores each provider webhook event per recipient. */
    public boolean hasProviderEvents() {
        return PROVIDER_EVENTS_LAYOUT.equals(schemaVersion) || perChannelTemplates();
    }

    /**
     * Layout "8": deliveries carry the channel's template (template_id or global_template_id) and
     * subject as columns, content has no subject, and notifications has no template_id or
     * recipient_count.
     */
    public boolean perChannelTemplates() {
        return PER_CHANNEL_TEMPLATES_LAYOUT.equals(schemaVersion);
    }

    /** Layout "5" only: notification_recipients also carry product_id (and their own created_at). */
    public boolean recipientsHaveProduct() {
        return CHANNEL_DELIVERIES_LAYOUT.equals(schemaVersion);
    }

    public boolean moduleEnabled(String moduleCode) {
        return enabledModules.contains(moduleCode);
    }
}
