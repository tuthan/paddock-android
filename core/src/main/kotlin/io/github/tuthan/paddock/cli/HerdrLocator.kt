package io.github.tuthan.paddock.cli

import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.SshSession
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Where herdr is installed on the host. A non-interactive SSH command often has a short PATH (on macOS it is `/usr/bin:/bin:/usr/sbin:/sbin`, so a Homebrew
 * install in `/opt/homebrew/bin` is invisible to it), so the usual install locations are checked by absolute path first. When none holds a herdr, the user's
 * own login shell is asked, which reads the profile that puts herdr on the PATH of their terminal (Homebrew's `brew shellenv`, mise, nix, a custom prefix).
 */
object HerdrLocator {
    /** Where herdr usually is, in the order tried; `$HOME` is expanded by the host's shell. */
    val CANDIDATES = listOf(
        "\$HOME/.local/bin/herdr", // herdr's own installer
        "\$HOME/.cargo/bin/herdr",
        "/opt/homebrew/bin/herdr", // Homebrew on Apple silicon
        "/usr/local/bin/herdr", // Homebrew on Intel Macs, and a copy put there by hand
        "/home/linuxbrew/.linuxbrew/bin/herdr", // Homebrew on Linux
        "/usr/bin/herdr",
    )

    /** The shell loop that prints the first executable among [candidates] and exits 0, or exits 1. */
    fun script(candidates: List<String> = CANDIDATES): String =
        "for p in " + candidates.joinToString(" ") { "\"$it\"" } + "; do [ -x \"\$p\" ] && { printf %s \"\$p\"; exit 0; }; done; exit 1"

    val SCRIPT = script()

    /**
     * The login-shell question: only for a shell whose `-lc` and `command -v` are standard, and the last line of what it printed (a profile may print a banner
     * first), accepted only as an absolute path to an executable. No single quote or backslash, which the SSH layer refuses in an argument.
     */
    const val LOGIN_SHELL_SCRIPT = "s=\$SHELL; case \"\${s##*/}\" in bash|zsh|fish|sh|dash|ksh) ;; *) exit 1;; esac; case \"\$s\" in /*) ;; *) exit 1;; esac; " +
        "p=\$(\"\$s\" -lc \"command -v herdr\" 2>/dev/null | tail -n 1); case \"\$p\" in /*) [ -x \"\$p\" ] && { printf %s \"\$p\"; exit 0; };; esac; exit 1"

    /** The folders [CANDIDATES] name, for a message: `~/.local/bin, ~/.cargo/bin, /opt/homebrew/bin, ...`. */
    fun searched(candidates: List<String> = CANDIDATES): String =
        candidates.map { it.substringBeforeLast('/').replace("\$HOME", "~") }.distinct().joinToString(", ")

    fun notFoundMessage(candidates: List<String> = CANDIDATES): String =
        "herdr was not found on the host (looked in ${searched(candidates)} and the PATH of the login shell). If it is installed elsewhere, link it into ~/.local/bin."

    /** The absolute path of the herdr binary, or null when none of the usual places holds one and the login shell does not know it either. */
    suspend fun find(session: SshSession): String? {
        asPath(session.exec(listOf("sh", "-c", SCRIPT), limits = ExecLimits(stdoutMax = 4096, stderrMax = 4096)))?.let { return it }
        val viaShell = try {
            session.exec(listOf("sh", "-c", LOGIN_SHELL_SCRIPT), limits = ExecLimits(stdoutMax = 4096, stderrMax = 4096, deadline = 10.seconds))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null // a login shell that hangs or fails is "not found", not a failed connection
        }
        return asPath(viaShell)
    }

    private fun asPath(r: ExecResult): String? =
        if (r.exit != 0) null else r.stdout.toString(Charsets.UTF_8).trim().takeIf { it.startsWith("/") && '\n' !in it }
}
