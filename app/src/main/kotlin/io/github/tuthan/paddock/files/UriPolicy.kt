package io.github.tuthan.paddock.files

/** Why a URI from a picker or a share was not taken (SEC-1). The sentence is the same for every reason: nobody needs to be told which check caught it. */
enum class UriRefusal {
    /** No URI, or one that is not `content://`: a `file://` path is refused outright (SEC-1), and so is anything else. */
    NotContent,

    /** The authority is one of Paddock's own providers, or the platform resolves it to Paddock's package: a sender asking Paddock to read its own files. */
    OwnProvider,

    /** The platform could not say who owns the authority, so it is not trusted. */
    Unverified,
    ;

    val sentence: String get() = "Paddock could not take that file."
}

/**
 * SEC-1, the checks on a URI that another app handed over, pure so a unit test runs every case. Google's guidance for a received URI is to refuse one
 * that targets the app itself, by authority and by `resolveContentProvider`; this adds `content://` only and treats a failed resolution as a refusal.
 * A null resolution means no installed provider has the authority, so the open can only succeed through the sender's own grant: that is another app's.
 * Nothing here reads the URI's content or the hints a sender attaches to it (name, type, size decide nothing).
 */
object UriPolicy {
    /**
     * Null when the URI may be opened. [scheme] and [authority] are the URI's own parts, [ownAuthorities] are every provider authority declared by
     * Paddock's package, and [resolvePackage] is `PackageManager.resolveContentProvider(authority, 0)?.packageName`.
     */
    fun check(
        scheme: String?,
        authority: String?,
        ownAuthorities: Set<String>,
        ownPackage: String,
        resolvePackage: (String) -> String?,
    ): UriRefusal? {
        if (scheme != "content") return UriRefusal.NotContent
        if (authority.isNullOrEmpty()) return UriRefusal.NotContent
        // `content://10@authority/...` addresses the provider of another user (the platform strips what comes before the last `@`), so the
        // comparison is on what the resolver will route on, not on the raw string.
        val routed = authority.substringAfterLast('@')
        if (routed.isEmpty()) return UriRefusal.NotContent
        if (ownAuthorities.any { it.equals(routed, ignoreCase = true) }) return UriRefusal.OwnProvider
        val owner = try { resolvePackage(routed) } catch (_: RuntimeException) { return UriRefusal.Unverified }
        if (owner == ownPackage) return UriRefusal.OwnProvider
        return null
    }
}
