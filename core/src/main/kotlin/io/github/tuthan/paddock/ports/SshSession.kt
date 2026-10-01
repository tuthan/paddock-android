package io.github.tuthan.paddock.ports

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One SSH session to one host profile. The adapter in `:app` implements it with the chosen library;
 * `:core` only ever sees this port. Arguments are argv lists, never shell strings: free text travels on stdin.
 */
interface SshSession {
    val link: StateFlow<LinkState>

    /** Runs [argv] to completion within [limits]. Exit status, stdout and stderr stay separate. */
    suspend fun exec(argv: List<String>, stdin: ByteArray? = null, limits: ExecLimits = ExecLimits.default): ExecResult

    /** Starts [argv] and leaves its channel open for streaming. */
    suspend fun openStream(argv: List<String>): StreamChannel

    suspend fun close()
}

sealed interface LinkState {
    val sinceMillis: Long

    data class Connecting(override val sinceMillis: Long) : LinkState
    data class Up(override val sinceMillis: Long) : LinkState
    data class Down(val reason: DownReason, override val sinceMillis: Long) : LinkState
}

sealed interface DownReason {
    /** Closed by us. */
    data object Closed : DownReason
    /** Keepalive or connect deadline passed without an answer. */
    data object Timeout : DownReason
    data object Refused : DownReason
    data object AuthFailed : DownReason
    /** The pinned host key no longer matches; no authentication was attempted. */
    data object HostKeyChanged : DownReason
    /** The Android local-network grant is missing; refused before any socket opened. */
    data object PermissionDenied : DownReason
    data class Network(val message: String) : DownReason
}

data class ExecLimits(
    val stdoutMax: Int = 1 shl 20,
    val stderrMax: Int = 64 shl 10,
    val deadline: Duration = 20.seconds,
) {
    companion object {
        val default = ExecLimits()
    }
}

class ExecResult(
    val exit: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val elapsed: Duration,
)

interface StreamChannel {
    val stdout: Flow<ByteArray>
    val stderr: Flow<ByteArray>
    suspend fun write(bytes: ByteArray)
    suspend fun closeStdin()
    /** Suspends until the remote command exits and returns its status. */
    suspend fun awaitExit(): Int
    suspend fun close()
}
