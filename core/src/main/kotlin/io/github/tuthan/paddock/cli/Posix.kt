package io.github.tuthan.paddock.cli

/**
 * Quoting for the one place a remote command line is built. sshd hands the line to the account's login shell (`$SHELL -c`),
 * which may be sh, bash, dash, zsh, fish or tcsh, so the output must mean the same thing in every one of them:
 *
 * - A bare word is only `[A-Za-z0-9_./-]`. Other punctuation is special somewhere (zsh expands an unquoted `=word`, fish
 *   and tcsh give `{`, `%`, `~`, `!` their own meanings).
 * - Everything else is wrapped in single quotes, the one form with the same meaning everywhere, provided the text holds no
 *   `'` and no `\`: inside single quotes fish treats `\'` and `\\` as escapes, and there is no way to embed a quote that
 *   works in every shell. Such an argument is rejected.
 * - NUL, CR and LF are rejected. Free text is never an argument: it travels on stdin, so any of these is a bug.
 */
fun posixQuote(arg: String): String {
    require('\u0000' !in arg) { "argument contains NUL" }
    require('\n' !in arg && '\r' !in arg) { "argument contains a line break" }
    require('\'' !in arg) { "argument contains a single quote, which no quoting form carries through every login shell" }
    require('\\' !in arg) { "argument contains a backslash, which fish reads as an escape inside single quotes" }
    if (arg.isEmpty()) return "''"
    if (arg.all { it in SAFE }) return arg
    return "'$arg'"
}

fun argvToCommand(argv: List<String>): String {
    require(argv.isNotEmpty()) { "empty argv" }
    return argv.joinToString(" ") { posixQuote(it) }
}

private val SAFE: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "_./-".toList()).toSet()
