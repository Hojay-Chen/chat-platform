package com.luxera.companion.contracts.errors;

/** Canonical error code constants. Keep stable — clients may branch on these. */
public final class PlatformErrorCodes {
    private PlatformErrorCodes() {}

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String VALIDATION = "VALIDATION";
    public static final String CONFLICT = "CONFLICT";
    public static final String INTERNAL = "INTERNAL";
    public static final String PROVISION_FAILED = "PROVISION_FAILED";
    public static final String PROVISIONING_IN_PROGRESS = "PROVISIONING_IN_PROGRESS";
}
