package com.itsluminous.hikviewer.core

/**
 * Allow-list of cloud hosts the protocol may ever talk to.
 *
 * The API tells the client where to go next in several places (`loginArea.apiDomain` on a region
 * redirect, `authAddr` in the service URLs, the VTM relay in the resource page list). A tampered or
 * buggy response must not make us send the password hash or the `sessionId` to an arbitrary host,
 * so every such hop is checked here first. The list mirrors cloud-cam-viewer's `safe_domain`.
 */
public object CloudDomains {
    private val allowedSuffixes =
        listOf("ezvizlife.com", "ezviz.com", "hik-connect.com", "hik-connectru.com", "hikvision.com")
    private val label = Regex("^[a-z0-9-]+$")

    /**
     * Normalises [host] (strips an `https://` prefix, trailing slash, case) and returns it when it is
     * a sub-domain of an allowed suffix; throws [UnsafeDomainException] otherwise.
     */
    public fun requireAllowed(host: String): String {
        val normalised =
            host
                .trim()
                .lowercase()
                .removePrefix("https://")
                .removePrefix("http://")
                .trimEnd('/')
        if (!isAllowed(normalised)) throw UnsafeDomainException(host)
        return normalised
    }

    /** True when [host] is a well-formed sub-domain of one of the allowed suffixes. */
    public fun isAllowed(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 3 || labels.any { !label.matches(it) }) return false
        val suffix = labels.takeLast(2).joinToString(".")
        return suffix in allowedSuffixes
    }
}

/** A cloud response pointed at a host outside the Hikvision/EZVIZ allow-list. */
public class UnsafeDomainException(
    host: String,
) : IllegalArgumentException("Cloud host not allowed: $host")
