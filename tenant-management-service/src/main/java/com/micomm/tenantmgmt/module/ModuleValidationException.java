package com.micomm.tenantmgmt.module;

/** A module request that cannot be applied as asked (unknown module, unsupported schema, ...). */
public class ModuleValidationException extends RuntimeException {

    public ModuleValidationException(String message) {
        super(message);
    }
}
