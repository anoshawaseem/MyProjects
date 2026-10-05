package com.micomm.tenantmgmt.provider;

public class ProviderNotConfiguredException extends RuntimeException {
    public ProviderNotConfiguredException(String message) {
        super(message);
    }
}