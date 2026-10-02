package io.github.tuthan.paddock.ops

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The user's own prompt snippets: up to [MAX_COUNT], each at most [MAX_LENGTH] characters, edited in Settings and
 * inserted into the composer. They are kept on this phone, never synced, and never read by anything but the composer.
 */
object Snippets {
    const val MAX_COUNT = 50
    const val MAX_LENGTH = 500

    sealed interface Change {
        data class Done(val items: List<String>) : Change
        data class Refused(val reason: String) : Change
    }

    fun add(items: List<String>, text: String): Change = check(items, null, text) { items + it }

    fun edit(items: List<String>, index: Int, text: String): Change =
        if (index !in items.indices) Change.Refused("That snippet is gone.") else check(items, index, text) { items.toMutableList().also { l -> l[index] = it } }

    fun remove(items: List<String>, index: Int): List<String> = if (index in items.indices) items.filterIndexed { i, _ -> i != index } else items

    /** Moves one snippet to another position, for ordering the chips. */
    fun move(items: List<String>, from: Int, to: Int): List<String> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        return items.toMutableList().also { l -> l.add(to, l.removeAt(from)) }
    }

    /** What a stored list is read as: trimmed, no blanks, no overlong entry, no repeat, at most [MAX_COUNT]. */
    fun normalize(items: List<String>): List<String> =
        items.map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_LENGTH }.distinct().take(MAX_COUNT)

    /** [snippet] appended to [text] with one space between them, or alone when the composer is empty. */
    fun insert(text: String, snippet: String): String = when {
        text.isEmpty() -> snippet
        text.last().isWhitespace() -> text + snippet
        else -> "$text $snippet"
    }

    private fun check(items: List<String>, replacing: Int?, text: String, apply: (String) -> List<String>): Change {
        val t = text.trim()
        return when {
            t.isEmpty() -> Change.Refused("A snippet cannot be empty.")
            t.length > MAX_LENGTH -> Change.Refused("A snippet is at most $MAX_LENGTH characters; this one is ${t.length}.")
            items.withIndex().any { (i, s) -> s == t && i != replacing } -> Change.Refused("That snippet is already saved.")
            replacing == null && items.size >= MAX_COUNT -> Change.Refused("You can keep $MAX_COUNT snippets. Remove one to add another.")
            else -> Change.Done(apply(t))
        }
    }
}

@Serializable
data class SnippetData(val items: List<String> = emptyList())

interface SnippetStore {
    suspend fun load(): List<String>
    suspend fun save(items: List<String>)
}

class InMemorySnippetStore(initial: List<String> = emptyList()) : SnippetStore {
    @Volatile private var current = initial
    override suspend fun load() = current
    override suspend fun save(items: List<String>) { current = items }
}

/**
 * One small JSON file. A missing or unreadable file is an empty list: snippets are the user's own phrases and
 * nothing depends on them. A write is moved over the old file, so a crash leaves the old list or the new one.
 */
class FileSnippetStore(private val file: File) : SnippetStore {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    override suspend fun load(): List<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) emptyList()
            else try { Snippets.normalize(json.decodeFromString(SnippetData.serializer(), file.readText()).items) } catch (_: RuntimeException) { emptyList() } catch (_: IOException) { emptyList() }
        }
    }

    override suspend fun save(items: List<String>) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                file.absoluteFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "cannot create ${it.path}" } }
                val tmp = File(file.absolutePath + ".tmp")
                tmp.writeText(json.encodeToString(SnippetData.serializer(), SnippetData(Snippets.normalize(items))))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
