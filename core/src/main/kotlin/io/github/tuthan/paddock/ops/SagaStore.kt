package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.storage.DurableFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The steps of the start-agent saga, in order. A saga's record names the step it is at or failed at. */
@Serializable
enum class SagaStep(val label: String) {
    Availability("check the executable"), Folder("check the folder"), Place("create the place"), Verify("check the pane"), Start("start the agent"), FirstPrompt("send the first prompt")
}

@Serializable
enum class SagaState { Running, Succeeded, Failed }

/**
 * One start-agent saga as it is kept on the phone: what was asked and every id herdr returned, written after each step and before
 * the next begins, so the recovery card can name what exists even after the app was killed. The first prompt's text is never here:
 * metadata persists, prompt text does not by default (the plan's storage rule); [promptSha256] says whether one was meant.
 *
 * [failure] is a [SagaFailure] code and [message] the sentence or herdr's own text; both are empty while [state] is not Failed.
 * [recovered] is set once the user dealt with what a failed saga left (closed it, or chose to leave it), so the card goes away.
 */
@Serializable
data class SagaRecord(
    val id: String,
    val host: String,
    val session: String,
    val agentName: String,
    val kind: String,
    val workspaceId: String,
    val branch: String? = null,
    val repository: String? = null,
    val step: SagaStep = SagaStep.Availability,
    val state: SagaState = SagaState.Running,
    val failure: String? = null,
    val message: String = "",
    val createdWorkspaceId: String? = null,
    val createdTabId: String? = null,
    val createdPaneId: String? = null,
    val createdTerminalId: String? = null,
    val worktreePath: String? = null,
    /** The pane's working directory as herdr reported it when the place was created; the verify step compares against it. */
    val expectedCwd: String? = null,
    val terminalId: String? = null,
    val promptSha256: String? = null,
    /** A line about the first prompt when the agent started but the prompt did not go (not ready, refused, unknown). */
    val promptNote: String? = null,
    val startedAt: Long,
    val updatedAt: Long,
    val recovered: Boolean = false,
    /** The folder the user asked the agent to start in, as typed; null for the workspace's own. [expectedCwd] holds what the host made of it. */
    val folder: String? = null,
) {
    /** Anything herdr created that still exists as far as this record knows. */
    val createdSomething get() = createdWorkspaceId != null || createdTabId != null || createdPaneId != null
    val needsRecovery get() = state == SagaState.Failed && !recovered && createdSomething

    /** A failed attempt nobody has dealt with yet (closed, left or dismissed): what a card is for, whether or not anything was created. */
    val unresolved get() = state == SagaState.Failed && !recovered

    /**
     * The create request left (or may have: the app ended while it was out) and no answer came back, so something may exist on the host that this phone has no
     * id for. Not the same as nothing created: the card says it is not known, and it stays until the user has looked and dismissed it.
     */
    val createUnknown get() = !createdSomething && (failure == SagaFailure.PLACE_UNKNOWN || (failure == SagaFailure.APP_ENDED && step == SagaStep.Place))

    /**
     * Whether the Spaces tab shows a card for it: every failed attempt that is not dealt with is, until the user dismisses it. One that left something
     * ([createdSomething]) or whose create outcome is not known ([createUnknown]) stays as long as it takes. One that stopped before creating anything (an
     * executable or a folder that is not there, a refusal herdr gave) is a certain "nothing happened": it has a card for [QUIET_AFTER_MILLIS] after it
     * failed, long enough to be seen on the next visit and to use "Start anyway", and is then left to Activity, so a pile of old refusals does not sit on the
     * tab. The screen does not decide this: it must not depend on how the user got to the tab, or a failure shown a second ago is gone after a tab switch.
     */
    fun showsCard(nowMillis: Long) = unresolved && (createdSomething || createUnknown || nowMillis - updatedAt <= QUIET_AFTER_MILLIS)

    companion object {
        const val QUIET_AFTER_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}

@Serializable
data class SagaData(val records: List<SagaRecord> = emptyList())

interface SagaStore {
    /** Everything saved. An unreadable file is moved aside (one `.corrupt` copy kept) and reads as empty: the journal still holds every operation. */
    fun load(): SagaData
    /** Durable on return, or an [IOException]. */
    fun save(data: SagaData)
}

class InMemorySagaStore(private var data: SagaData = SagaData()) : SagaStore {
    @Volatile var failSaves = false
    override fun load() = data
    override fun save(data: SagaData) { if (failSaves) throw IOException("disk full"); this.data = data }
}

class FileSagaStore(private val file: File) : SagaStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun load(): SagaData {
        if (!file.exists()) return SagaData()
        return try {
            json.decodeFromString(SagaData.serializer(), file.readText())
        } catch (e: RuntimeException) {
            setAside(); SagaData()
        } catch (e: IOException) {
            setAside(); SagaData()
        }
    }

    override fun save(data: SagaData) { DurableFile.replace(file, json.encodeToString(SagaData.serializer(), data).toByteArray(Charsets.UTF_8)) }

    private fun setAside() {
        try {
            Files.move(file.toPath(), File(file.absolutePath + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING)
            DurableFile.syncDirectory(file.absoluteFile.parentFile)
        } catch (_: IOException) { /* the file stays where it is; the next save replaces it atomically */ }
    }
}
