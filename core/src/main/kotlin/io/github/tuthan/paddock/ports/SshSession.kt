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

    /**
     * Runs [argv] to completion within [limits]. Exit status, stdout and stderr stay separate. [ExecLimits.deadline] covers
     * waiting for the channel to open as well as the command; past it the channel is closed. Closing a channel does not
     * signal the remote process (there is no pty), so a command that ignores its closed pipes runs on until it exits.
     */
    suspend fun exec(argv: List<String>, stdin: ByteArray? = null, limits: ExecLimits = ExecLimits.default): ExecResult

    /**
     * Starts [argv] and leaves its channel open for streaming. The caller owns the channel and must [StreamChannel.close]
     * it, normally in a `finally`. Channels are a bounded resource; an implementation may fail this call with an
     * IOException when none frees up in reasonable time instead of waiting forever.
     */
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
    /**
     * The key this phone signs with cannot be used: the phone key is gone (app data lost) or the Keystore refused it, or the
     * imported key's stored copy cannot be read. Nothing was signed in; only the user can fix it.
     */
    data object KeyUnavailable : DownReason
    /** The saved host keys cannot be read, so no host can be verified. Nothing was signed in; only the user can fix it. */
    data object HostKeysUnreadable : DownReason
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

/**
 * One streaming command. Every suspending member, and collecting either flow, MUST return promptly (well under a second)
 * when its coroutine is cancelled, even while the remote side is silent: callers put `withTimeout` around reads and replace
 * subscriptions by cancelling them, and an implementation that waits for blocked I/O to finish defeats both. Cancelling a
 * collector does not close the channel; [close] does.
 */
interface StreamChannel {
    /** The command's stdout, in order. Collect it once; it completes when the remote side closes stdout. */
    val stdout: Flow<ByteArray>
    /** As [stdout], for stderr. */
    val stderr: Flow<ByteArray>
    suspend fun write(bytes: ByteArray)
    suspend fun closeStdin()
    /** Suspends until the remote command exits and returns its status. */
    suspend fun awaitExit(): Int
    /**
     * Closes the channel and releases everything it holds (the SSH channel and its slot) without waiting for the remote
     * side. Idempotent. Pending reads end; the remote process is not signalled.
     */
    suspend fun close()
}
