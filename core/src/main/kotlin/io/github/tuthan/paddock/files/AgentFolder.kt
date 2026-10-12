package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.herdr.Agent

/**
 * The folder an attachment goes under and a path is judged against: where the agent's foreground process is, else where its pane started
 * (herdr's `foreground_cwd` and `cwd`; the same pair `Attention` shows). Null when herdr reports none, and then Attach is off with its reason.
 * It must also be an absolute path without a control character, which is all the transfer scripts accept ([TransferScripts]): a folder
 * they would refuse is not offered.
 */
fun Agent.folder(): String? = (foregroundCwd ?: cwd)?.takeIf { it.startsWith("/") && it.none { c -> c.isISOControl() } }
