package com.itsluminous.hikviewer.core.auth

/**
 * Hik-Connect regions and the API host each one is served from.
 *
 * The hosts come from the official app (its debug "select API" activity lists them) and from the
 * cloud-cam-viewer protocol notes. Logging in on the wrong host is harmless: the server answers
 * `meta.code 1100/101100` with the right `loginArea.apiDomain` and [HikConnectClient] follows it
 * once. India is the default because it is the region this app was built for.
 */
public enum class Region(
    /** Human-readable name for the region picker. */
    public val displayName: String,
    /** Regional API host without scheme. */
    public val apiHost: String,
) {
    /** India (`areaId` 244, +91). `api.hik-connect.com` resolves here from India already. */
    INDIA("India", "apiiindia.hik-connect.com"),

    /** Global entry point; the server redirects to the account's real region. */
    GLOBAL("Global / auto-detect", "api.hik-connect.com"),

    /** Singapore / Asia-Pacific. */
    SINGAPORE("Asia-Pacific (Singapore)", "apiisgp.hik-connect.com"),

    /** United States. */
    USA("United States", "apiitus.hik-connect.com"),

    /** Mainland China. */
    CHINA("China", "apiitcn.hik-connect.com"),

    /** Russia, served from a separate top-level domain. */
    RUSSIA("Russia", "apiirus.hik-connectru.com"),
    ;

    /** Defaults. */
    public companion object {
        /** Region offered when the user has not chosen one. */
        public val DEFAULT: Region = INDIA
    }
}
