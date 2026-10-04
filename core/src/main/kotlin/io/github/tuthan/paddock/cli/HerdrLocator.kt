package io.github.tuthan.paddock.cli

import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession

/** Where herdr is installed on the host: a non-interactive SSH command often has a short PATH, so the usual install locations are checked by absolute path. */
object HerdrLocator {
    const val SCRIPT = "for p in \"\$HOME/.local/bin/herdr\" \"\$HOME/.cargo/bin/herdr\" /usr/local/bin/herdr /usr/bin/herdr; do [ -x \"\$p\" ] && { printf %s \"\$p\"; exit 0; }; done; exit 1"

    /** The absolute path of the herdr binary, or null when none of the usual places holds one. */
    suspend fun find(session: SshSession): String? {
        val r = session.exec(listOf("sh", "-c", SCRIPT), limits = ExecLimits(stdoutMax = 4096, stderrMax = 4096))
        if (r.exit != 0) return null
        return r.stdout.toString(Charsets.UTF_8).trim().takeIf { it.startsWith("/") && '\n' !in it }
    }
}
