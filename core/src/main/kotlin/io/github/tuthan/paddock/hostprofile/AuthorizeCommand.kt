package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.ssh.AuthorizedKey

/**
 * The command that authorizes this phone on a machine: what the Copy and Share buttons put out. One line of POSIX `sh`:
 * creates `~/.ssh` (700) when it is missing and sets it to 700, creates `authorized_keys` and sets it to 600, adds a
 * newline first only when the file does not already end with one, and appends the key line only when no line of the file
 * equals it exactly. Run twice it changes nothing the second time; every other line stays as it was.
 *
 * It is built only from an [AuthorizedKey], whose alphabet has no quote, backslash, `$`, backtick or whitespace but the
 * separating spaces, so the key line sits inside single quotes with nothing to escape. [forText] returns null for anything
 * [AuthorizedKey.parse] refuses: no command exists for a hostile comment or another key type.
 */
object AuthorizeCommand {
    private const val FILE = "~/.ssh/authorized_keys"

    fun forKey(key: AuthorizedKey): String {
        val line = key.line
        check('\'' !in line && '\\' !in line) { "an accepted key line has no quote or backslash" }
        return "umask 077; mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch $FILE && chmod 600 $FILE && " +
            "{ grep -qxF '$line' $FILE || { [ -z \"\$(tail -c1 $FILE)\" ] || echo >> $FILE; printf '%s\\n' '$line' >> $FILE; }; }"
    }

    fun forText(keyLine: String?): String? = AuthorizedKey.parse(keyLine)?.let(::forKey)
}
