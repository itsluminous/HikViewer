package com.itsluminous.hikviewer.core.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Supplies the per-install `featureCode`.
 *
 * Hik-Connect treats the feature code as the identity of the "terminal" (device) that logs in.
 * It must be stable across restarts, or every launch looks like a new device and the cloud starts
 * demanding CAPTCHAs and SMS codes; and it must be unique per install, or unrelated users share one
 * terminal and trip each other's rate limits. Generating and persisting it is the caller's job
 * (`:app` keeps it in private preferences); `:core` only needs to read it.
 */
public fun interface FeatureCodeStore {
    /** The stable, lowercase-hex feature code of this installation. */
    public fun featureCode(): String
}

/**
 * Maps an API host name to the base URL requests are sent to.
 *
 * Production uses `https://host/`; tests point every host at a MockWebServer. The host is checked
 * against [com.itsluminous.hikviewer.core.CloudDomains] *before* this is called, so a resolver never
 * sees an unknown host.
 */
public fun interface HostResolver {
    /** Base URL (with trailing slash) for [apiHost]. */
    public fun baseUrl(apiHost: String): HttpUrl

    /** Built-in resolvers. */
    public companion object {
        /** Plain `https://host/`. */
        public val HTTPS: HostResolver = HostResolver { host -> "https://$host/".toHttpUrl() }
    }
}

/**
 * An authenticated Hik-Connect session.
 *
 * [sessionId] is a JWT; it goes into the `sessionId` header of every API call and its `s` claim
 * signs the VTDU token request. [refreshSessionId] renews it without the password. [apiDomain] is
 * the regional host that issued the session; all later calls must go there.
 */
public data class Session(
    val sessionId: String,
    val refreshSessionId: String,
    val apiDomain: String,
    val userId: String?,
    val areaId: Int?,
) {
    override fun toString(): String = "Session(apiDomain=$apiDomain, userId=${userId?.let { "set" }}, areaId=$areaId)"
}

/** Why a login or session renewal failed. Every case is user-presentable without leaking data. */
public sealed class LoginError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * Server said the account or password is wrong (`meta.code` 1013/1014/1226). The most common
     * cause for a phone-number account is a missing country code, hence the hint.
     */
    public class InvalidCredentials(
        /** Hint for the UI; mentions that the account must include the country code. */
        public val hint: String = COUNTRY_CODE_HINT,
    ) : LoginError("Wrong account or password. $hint")

    /** The server wants a second factor we cannot complete from this client. */
    public class NeedsVerification(
        /** Which kind of verification the server asked for. */
        public val kind: VerificationKind,
        /** Raw `meta.code`, for diagnostics. */
        public val metaCode: Int,
    ) : LoginError("Hik-Connect asks for $kind (code $metaCode); complete it in the official app, then retry")

    /** The server answered with an unexpected `meta.code`. */
    public class Server(
        /** Raw `meta.code`, or null when the body had no meta block. */
        public val metaCode: Int?,
        /** Server-provided message, if any. */
        public val serverMessage: String?,
    ) : LoginError("Hik-Connect error ${metaCode ?: "?"}${serverMessage?.let { ": $it" } ?: ""}")

    /** The server redirected to another region more than once. */
    public class RedirectLoop(
        /** Hosts visited before giving up. */
        public val hosts: List<String>,
    ) : LoginError("Region redirect loop: ${hosts.joinToString(" -> ")}")

    /** The refresh token was rejected; the user must log in again. */
    public class SessionExpired(
        /** Raw `meta.code` or HTTP status that rejected the refresh. */
        public val code: Int?,
    ) : LoginError("Session expired (code $code); log in again")

    /** Transport failure (DNS, TLS, timeout, connection reset) or an unparsable body. */
    public class Network(
        cause: Throwable,
    ) : LoginError("Network error: ${cause.message ?: cause::class.simpleName}", cause)

    /** Shared message fragments. */
    public companion object {
        /** The hint shown next to [InvalidCredentials]. */
        public const val COUNTRY_CODE_HINT: String =
            "If you log in with a phone number, include the country code (for example 91XXXXXXXXXX)."
    }
}

/** Second-factor flows Hik-Connect may demand at login. */
public enum class VerificationKind {
    /** `meta.code` 1015: an image CAPTCHA (`GET /v3/captcha`). */
    CAPTCHA,

    /** `meta.code` 6002: SMS / e-mail one-time code (account has MFA on). */
    SMS_CODE,

    /** `meta.code` 1069: the account hit its terminal limit; a new terminal must be approved. */
    TERMINAL_BINDING,
}
