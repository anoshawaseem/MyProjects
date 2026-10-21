package com.micomm.email.tenant;

import java.util.List;
import java.util.Locale;

import com.micomm.email.client.dto.TenantDatabaseDto;

/**
 * Product-module rules shared by templates, sends and notification reads. Only
 * club-scoped schemas ("3") have modules; a module must be enabled for the club
 * + product before anything can be read or written under it.
 */
public final class TenantModules {

    private TenantModules() {
    }

    /** Upper-cases a module code from a request; blank means "no module". */
    public static String normalize(String moduleCode) {
        return moduleCode == null || moduleCode.isBlank() ? null : moduleCode.trim().toUpperCase(Locale.ROOT);
    }

    /** Returns the module if the schema supports modules and the club has it enabled. */
    public static String requireEnabled(TenantDatabaseDto tenant, String moduleCode) {
        if (!tenant.supportsModules()) {
            throw new ModuleNotSupportedException();
        }
        if (!tenant.moduleEnabled(moduleCode)) {
            throw new ModuleNotEnabledException(moduleCode);
        }
        return moduleCode;
    }

    /**
     * Modules a club-scoped request may see: just the named module (which must be
     * enabled), or every enabled module when none is named.
     */
    public static List<String> visible(TenantDatabaseDto tenant, String moduleCode) {
        return moduleCode == null ? tenant.enabledModules() : List.of(requireEnabled(tenant, moduleCode));
    }

    public static class ModuleNotEnabledException extends RuntimeException {
        public ModuleNotEnabledException(String moduleCode) {
            super("Module " + moduleCode + " is not enabled for this club and product");
        }
    }

    public static class ModuleNotSupportedException extends RuntimeException {
        public ModuleNotSupportedException() {
            super("This club's schema predates modules; modules are available for clubs created since");
        }
    }
}
