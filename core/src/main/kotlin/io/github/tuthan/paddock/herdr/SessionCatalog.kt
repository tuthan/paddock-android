package io.github.tuthan.paddock.herdr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Placeholder for `herdr session list --json`; Phase 03 replaces it with the real catalog model. */
@Serializable
data class SessionCatalog(val sessions: List<SessionEntry>)

@Serializable
data class SessionEntry(
    val name: String,
    val default: Boolean,
    val running: Boolean,
    @SerialName("session_dir") val sessionDir: String,
    @SerialName("socket_path") val socketPath: String,
)
