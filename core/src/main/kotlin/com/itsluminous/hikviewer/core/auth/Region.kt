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
    /**
     * International dialling code the login form prepends to a bare mobile number (the
     * `telephoneCode` column of the official app's `assets/area.xml`: a Hik-Connect phone account
     * is `telephoneCode + number`, digits only, no `+`). Null where the region does not name one
     * country, so the number is sent as typed.
     */
    public val telephoneCode: String?,
) {
    /** India (`areaId` 244, +91). `api.hik-connect.com` resolves here from India already. */
    INDIA("India", "apiiindia.hik-connect.com", "91"),

    /** Global entry point; the server redirects to the account's real region. No country, no code. */
    GLOBAL("Global / auto-detect", "api.hik-connect.com", null),

    /** Singapore / Asia-Pacific. */
    SINGAPORE("Asia-Pacific (Singapore)", "apiisgp.hik-connect.com", "65"),

    /** United States. */
    USA("United States", "apiitus.hik-connect.com", "1"),

    /** Mainland China. */
    CHINA("China", "apiitcn.hik-connect.com", "86"),

    /** Russia, served from a separate top-level domain. */
    RUSSIA("Russia", "apiirus.hik-connectru.com", "7"),
    ;

    /** Defaults. */
    public companion object {
        /** Region offered when the user has not chosen one. */
        public val DEFAULT: Region = INDIA
    }
}
