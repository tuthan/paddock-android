package io.github.tuthan.paddock.cli

/**
 * POSIX shell quoting for the one place a remote command line is built. Free text is never an argument:
 * it travels on stdin, so an argument holding NUL or a newline is a bug and is rejected.
 */
fun posixQuote(arg: String): String {
    require('\u0000' !in arg) { "argument contains NUL" }
    require('\n' !in arg && '\r' !in arg) { "argument contains a line break" }
    if (arg.isEmpty()) return "''"
    if (arg.all { it in SAFE }) return arg
    return "'" + arg.replace("'", "'\\''") + "'"
}

fun argvToCommand(argv: List<String>): String {
    require(argv.isNotEmpty()) { "empty argv" }
    return argv.joinToString(" ") { posixQuote(it) }
}

private val SAFE: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "_@%+=:,./-".toList()).toSet()
