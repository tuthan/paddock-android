package io.github.tuthan.paddock.host

import androidx.compose.runtime.saveable.Saver
import io.github.tuthan.paddock.hostprofile.PairingLink

/**
 * A pairing link held by the screen, kept across rotation and process death: without it a link opened on a fresh install is gone after
 * the first rotation and the form falls back to Welcome. It was validated when it was read, and a saved state is this app's own.
 * The session stays null when the link had none, so the link's hash (which keys the form's saved fields) is the same after a restore.
 */
val PairingLinkSaver: Saver<PairingLink?, Any> = Saver(
    save = { l -> if (l == null) emptyList<String?>() else listOf(l.host, l.port.toString(), l.user, l.fingerprints.joinToString(","), l.session, l.pairPort?.toString(), l.sid) },
    restore = { v ->
        @Suppress("UNCHECKED_CAST") val l = v as List<String?>
        if (l.size != 7) null else runCatching {
            PairingLink(l[0]!!, l[1]!!.toInt(), l[2]!!, l[3]!!.split(",").filter { it.isNotEmpty() }, l[4], l[5]?.toInt(), l[6])
        }.getOrNull()
    },
)
