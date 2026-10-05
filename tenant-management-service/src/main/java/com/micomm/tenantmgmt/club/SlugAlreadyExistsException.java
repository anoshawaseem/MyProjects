package com.micomm.tenantmgmt.club;

public class SlugAlreadyExistsException extends RuntimeException {
    public SlugAlreadyExistsException(String slug) {
        super("Slug '" + slug + "' is already in use");
    }
}