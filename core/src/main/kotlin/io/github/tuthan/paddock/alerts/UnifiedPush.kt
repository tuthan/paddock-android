package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ports.Clock
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The UnifiedPush Android specification (AND_3.1.0) as far as Paddock uses it, written out here instead of pulled in as a library:
 * the connector library has no Socket result (404, 2026-10-02), so it has no review row, and what Paddock needs is five broadcast
 * actions and a token. Nothing in this file touches the platform; the app's receiver reads the intent into [PushExtras] and the
 * rules below decide what it means.
 */
object UnifiedPush {
    // The app sends these to the distributor ...
    const val REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    // ... and receives these from it.
    const val NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val TEMP_UNAVAILABLE = "org.unifiedpush.android.connector.TEMP_UNAVAILABLE"
    /** The package a registration's PendingIntent points at on Android 13 and older, where the sender's identity cannot be shared. */
    const val DUMMY_APP = "org.unifiedpush.dummy_app"

    val RECEIVED_ACTIONS = listOf(NEW_ENDPOINT, REGISTRATION_FAILED, MESSAGE, UNREGISTERED, TEMP_UNAVAILABLE)

    const val MAX_TOKEN_BYTES = 100
    const val MAX_ID_BYTES = 100
    const val MAX_ENDPOINT_BYTES = 1000
    const val MAX_MESSAGE_BYTES = 4096
    const val MAX_DESCRIPTION_BYTES = 100

    /** The registration's short description the distributor may show to the user. */
    fun description(machine: String): String = "Paddock: $machine".encodeToByteArray().let { if (it.size <= MAX_DESCRIPTION_BYTES) "Paddock: $machine" else "Paddock alerts" }
}

enum class PushFailure(val label: String) {
    InternalError("The distributor had an internal error. Try again."),
    Network("The distributor has no network. Try again when it is back."),
    ActionRequired("The distributor needs you to do something first. Open it, then try again."),
    VapidRequired("This distributor requires a server feature Paddock does not support, so it cannot be used for alerts."),
}

/** The extras of a broadcast, as the platform layer read them: its strings by key, and `bytesMessage` when there was one. */
class PushExtras(val strings: Map<String, String?>, val bytes: ByteArray? = null)

/** What a distributor sent, once it passed the specification's rules for that action. A request that broke a rule is [PushProtocol.parse]'s null. */
sealed interface PushInbound {
    val token: String
    data class Endpoint(override val token: String, val endpoint: String, val id: String?) : PushInbound
    class Message(override val token: String, val bytes: ByteArray, val id: String?) : PushInbound
    data class Failed(override val token: String, val reason: PushFailure) : PushInbound
    data class Unregistered(override val token: String) : PushInbound
    data class TempUnavailable(override val token: String) : PushInbound
}

object PushProtocol {
    private val TOKEN = Regex("[A-Za-z0-9._-]{1,${UnifiedPush.MAX_TOKEN_BYTES}}")
    private val ID = Regex("[A-Za-z0-9._:-]{1,${UnifiedPush.MAX_ID_BYTES}}")

    /** "Any request containing an extra that does not follow the specification MUST be ignored": every violation is a null. */
    fun parse(action: String?, extras: PushExtras): PushInbound? {
        val token = extras.strings["token"]?.takeIf { TOKEN.matches(it) } ?: return null
        val rawId = extras.strings["id"]
        if (rawId != null && !ID.matches(rawId)) return null
        return when (action) {
            UnifiedPush.NEW_ENDPOINT -> PushInbound.Endpoint(token, PushEndpoint.accept(extras.strings["endpoint"]) ?: return null, rawId)
            UnifiedPush.MESSAGE -> {
                val bytes = extras.bytes?.takeIf { it.size in 1..UnifiedPush.MAX_MESSAGE_BYTES } ?: return null
                PushInbound.Message(token, bytes, rawId)
            }
            UnifiedPush.REGISTRATION_FAILED -> PushInbound.Failed(token, when (extras.strings["reason"]) {
                "NETWORK" -> PushFailure.Network
                "ACTION_REQUIRED" -> PushFailure.ActionRequired
                "VAPID_REQUIRED" -> PushFailure.VapidRequired
                else -> PushFailure.InternalError
            })
            UnifiedPush.UNREGISTERED -> PushInbound.Unregistered(token)
            UnifiedPush.TEMP_UNAVAILABLE -> PushInbound.TempUnavailable(token)
            else -> null
        }
    }
}

/**
 * The address a distributor gives this phone, and so the address the host relay will POST to. The relay applies the same rule
 * (https, or http to a loopback host, no credentials in the address), so the phone never writes one the relay would refuse.
 */
object PushEndpoint {
    fun accept(url: String?): String? {
        if (url == null || url.length > UnifiedPush.MAX_ENDPOINT_BYTES || url.any { it.code < 0x21 || it.code > 0x7e }) return null
        val uri = try { URI(url) } catch (_: URISyntaxException) { return null }
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (uri.userInfo != null || uri.rawFragment != null) return null
        val loopback = host == "localhost" || host == "::1" || host.matches(Regex("127(\\.[0-9]{1,3}){3}"))
        return when (uri.scheme?.lowercase()) { "https" -> url; "http" -> url.takeIf { loopback }; else -> null }
    }
}

/**
 * What a push message says, which is almost nothing: the relay posts `{"v":1,"h":"<profile>","n":"<nonce>"}`. The token already
 * says which registration it is for, so the message's own profile is never used to pick a machine; the nonce is only for dedupe.
 * Anything else in the bytes, or bytes that are not this at all (a distributor that wrapped or encrypted it), still wakes the
 * alert: a push is a hint, and the content is never trusted for anything.
 */
data class PushHint(val profile: String?, val nonce: String?) {
    companion object {
        private val NONCE = Regex("[A-Za-z0-9]{1,64}")
        private val json = Json

        fun parse(bytes: ByteArray): PushHint {
            val obj = try { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject } catch (_: SerializationException) { null } catch (_: IllegalArgumentException) { null }
            if (obj == null) return PushHint(null, null)
            val h = (obj["h"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { HostProfile.ID.matches(it) }
            val n = (obj["n"] as? JsonPrimitive)?.let { p -> if (p.isString) p.contentOrNull else p.longOrNull?.toString() }?.takeIf { NONCE.matches(it) }
            return PushHint(h, n)
        }
    }
}

/** One machine's registration with a distributor. [endpoint] is what the distributor gave; [sharedAtMillis] is when the phone wrote it to the host. */
@Serializable
data class PushRegistration(
    val profile: String,
    val token: String,
    val distributor: String,
    val registeredAtMillis: Long,
    val endpoint: String? = null,
    val sharedAtMillis: Long? = null,
    val failure: PushFailure? = null,
)

interface PushStore {
    suspend fun load(): List<PushRegistration>
    suspend fun save(registrations: List<PushRegistration>)
}

class MemoryPushStore(var saved: List<PushRegistration> = emptyList()) : PushStore {
    override suspend fun load() = saved
    override suspend fun save(registrations: List<PushRegistration>) { saved = registrations }
}

class FilePushStore(private val file: File) : PushStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Serializable
    private class FileDto(val version: Int = 1, val registrations: List<PushRegistration> = emptyList())

    override suspend fun load(): List<PushRegistration> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        // A file that cannot be read is no registration: the user registers again, and a distributor that still holds the old
        // token has its messages ignored (an unknown token is dropped).
        try { json.decodeFromString(FileDto.serializer(), file.readText()).registrations } catch (_: IllegalArgumentException) { emptyList() }
    }

    override suspend fun save(registrations: List<PushRegistration>) { withContext(Dispatchers.IO) {
        file.absoluteFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "cannot create ${it.path}" } }
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(FileDto.serializer(), FileDto(registrations = registrations.sortedBy { it.profile })))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } }
}

/** A message the distributor wants acknowledged, to the distributor it came from, with the registration's own token. */
data class PushAck(val distributor: String, val token: String, val id: String)

/** What the app does after the registry has seen one inbound broadcast. */
sealed interface PushEffect {
    /** Not for a registration this phone has, or not a request the specification allows. */
    data object Ignore : PushEffect
    /** A push arrived for [profile]: raise the generic alert. [ack] is owed to the distributor. */
    data class Alert(val profile: String, val nonce: String?, val ack: PushAck?) : PushEffect
    /** The distributor gave (or changed) the address. When it was already on the host, [changedAfterShared] says the host has the old one. */
    data class EndpointKnown(val profile: String, val endpoint: String, val changedAfterShared: Boolean, val ack: PushAck?) : PushEffect
    data class Failed(val profile: String, val reason: PushFailure) : PushEffect
    data class Unregistered(val profile: String) : PushEffect
}

/**
 * The phone's registrations, one per machine, and the rules for what a distributor's broadcast does to them. The token is the only
 * authentication a connector has (a broadcast's sender cannot be checked), so a token is a fresh random UUID per registration,
 * never reused after a failure or an unregistration, and a broadcast carrying a token this phone does not hold is [PushEffect.Ignore].
 */
class PushRegistry(private val store: PushStore, private val newToken: () -> String = { java.util.UUID.randomUUID().toString() }, private val clock: Clock) {
    private val lock = Mutex()

    suspend fun all(): List<PushRegistration> = lock.withLock { store.load() }
    suspend fun forProfile(profile: String): PushRegistration? = all().firstOrNull { it.profile == profile }

    /** Registers [profile] with [distributor] under a new token, replacing any earlier registration of that machine (whose token is returned to be unregistered). */
    suspend fun begin(profile: String, distributor: String): Pair<PushRegistration, PushRegistration?> = lock.withLock {
        val all = store.load()
        val old = all.firstOrNull { it.profile == profile }
        val fresh = PushRegistration(profile, newToken(), distributor, clock.nowMillis())
        store.save(all.filterNot { it.profile == profile } + fresh)
        fresh to old
    }

    /** Forgets [profile]'s registration, as the user asked; returns it so the distributor can be told. The token is gone before the distributor answers. */
    suspend fun end(profile: String): PushRegistration? = lock.withLock {
        val all = store.load()
        val old = all.firstOrNull { it.profile == profile } ?: return@withLock null
        store.save(all - old)
        old
    }

    suspend fun markShared(profile: String) = lock.withLock {
        val all = store.load()
        store.save(all.map { if (it.profile == profile && it.endpoint != null) it.copy(sharedAtMillis = clock.nowMillis()) else it })
    }

    suspend fun onInbound(inbound: PushInbound): PushEffect = lock.withLock {
        val all = store.load()
        val reg = all.firstOrNull { it.token == inbound.token } ?: return@withLock PushEffect.Ignore
        suspend fun replace(new: PushRegistration?) = store.save(all.filterNot { it === reg } + listOfNotNull(new))
        fun ack(id: String?) = id?.let { PushAck(reg.distributor, reg.token, it) }
        when (inbound) {
            is PushInbound.Endpoint -> {
                val changed = reg.endpoint != null && reg.endpoint != inbound.endpoint
                val shared = if (changed) null else reg.sharedAtMillis
                replace(reg.copy(endpoint = inbound.endpoint, sharedAtMillis = shared, failure = null))
                PushEffect.EndpointKnown(reg.profile, inbound.endpoint, changed && reg.sharedAtMillis != null, ack(inbound.id))
            }
            is PushInbound.Message -> {
                // A message before an endpoint is not one the distributor could have routed to this registration.
                if (reg.endpoint == null) return@withLock PushEffect.Ignore
                PushEffect.Alert(reg.profile, PushHint.parse(inbound.bytes).nonce, ack(inbound.id))
            }
            is PushInbound.Failed -> {
                // After a NEW_ENDPOINT for the same token a failure is stale: ignored. Otherwise the token is changed for the next try.
                if (reg.endpoint != null) return@withLock PushEffect.Ignore
                replace(reg.copy(token = newToken(), failure = inbound.reason))
                PushEffect.Failed(reg.profile, inbound.reason)
            }
            is PushInbound.Unregistered -> { replace(null); PushEffect.Unregistered(reg.profile) }
            is PushInbound.TempUnavailable -> PushEffect.Ignore
        }
    }
}
