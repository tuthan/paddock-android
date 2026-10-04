package io.github.tuthan.paddock.widget

import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.attention.Section
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.storage.DurableFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How many agents were in each state at the read, as the herd counts them (a Done the user already saw is Ready). */
@Serializable
data class WidgetCounts(val needsYou: Int = 0, val done: Int = 0, val working: Int = 0, val ready: Int = 0, val unknown: Int = 0) {
    val total: Int get() = needsYou + done + working + ready + unknown
}

/** One blocked agent the widget can name and link to. [seq] is its `state_change_seq`, which the link carries as the alert's sequence. */
@Serializable
data class WidgetRow(
    val title: String,
    val kind: String? = null,
    val terminalId: String,
    val paneId: String,
    val seq: Long? = null,
    /** When the phone saw it blocked, or the time of the read that first showed it. */
    val observedAtMillis: Long,
)

/**
 * What a widget may show: the last successful read of the watched machine, kept on the phone and nothing else. A widget never holds a
 * socket and never invents a number; [readAtMillis] is the time of the read the counts come from, and every widget prints it.
 * Titles are the herd's own (cleaned, bounded); no prompt text, output or path is ever in here.
 */
@Serializable
data class WidgetCache(
    val version: Int = VERSION,
    val hostId: String,
    val machine: String,
    val session: String,
    val readAtMillis: Long,
    val counts: WidgetCounts,
    /** The most urgent blocked agents, at most [MAX_ROWS], in the herd's own order. */
    val urgent: List<WidgetRow> = emptyList(),
) {
    companion object {
        const val VERSION = 1
        const val MAX_ROWS = 2
        const val MAX_TITLE = 60
    }
}

object WidgetCacheBuilder {
    /** From the home list the app already shows: the same states, the same order, the same cleaned titles. */
    fun from(home: HomeModel, hostId: String, machine: String, session: String, readAtMillis: Long): WidgetCache {
        val rows = home.rows
        fun n(s: Section) = rows.count { it.section == s }
        val urgent = rows.filter { it.state == StateWord.Blocked }.take(WidgetCache.MAX_ROWS).map {
            WidgetRow(it.title.take(WidgetCache.MAX_TITLE), it.agentKind, it.key.target.terminalId, it.paneId, it.stateChangeSeq, it.observedAtMillis)
        }
        return WidgetCache(
            hostId = hostId, machine = machine.take(60), session = session, readAtMillis = readAtMillis,
            counts = WidgetCounts(n(Section.NeedsYou), n(Section.Done), n(Section.Working), n(Section.Ready), n(Section.Unknown)), urgent = urgent,
        )
    }
}

interface WidgetCacheStore {
    /** The last cache, or null when none was written, it is unreadable, or it is a version this build does not know. Never throws. */
    fun load(): WidgetCache?
    /** Durable on return, or an [IOException]. */
    fun save(cache: WidgetCache)
}

class InMemoryWidgetCacheStore(private var cache: WidgetCache? = null) : WidgetCacheStore {
    override fun load() = cache
    override fun save(cache: WidgetCache) { this.cache = cache }
}

/** `files/widget-cache.json`, replaced atomically (fsynced temp file, then rename), so a widget redraw never reads half a write. */
class FileWidgetCacheStore(private val file: File) : WidgetCacheStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun load(): WidgetCache? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString(WidgetCache.serializer(), file.readText()).takeIf { it.version == WidgetCache.VERSION }
        } catch (e: RuntimeException) {
            setAside(); null
        } catch (e: IOException) {
            null
        }
    }

    override fun save(cache: WidgetCache) { DurableFile.replace(file, json.encodeToString(WidgetCache.serializer(), cache).toByteArray(Charsets.UTF_8)) }

    private fun setAside() {
        try { Files.move(file.toPath(), File(file.absolutePath + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING) } catch (_: IOException) { /* the next save replaces it */ }
    }
}
