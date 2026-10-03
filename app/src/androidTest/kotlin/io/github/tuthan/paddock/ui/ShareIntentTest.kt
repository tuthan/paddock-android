package io.github.tuthan.paddock.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AC-11.4: Share sends the command text and nothing else. */
class ShareIntentTest {
    private val command = "umask 077; mkdir -p ~/.ssh && echo 'ecdsa-sha2-nistp256 AAAA paddock@phone'"

    @Test fun theIntentCarriesOnlyThePlainTextOfTheCommand() {
        val i = commandShareIntent(command)
        assertEquals(Intent.ACTION_SEND, i.action)
        assertEquals("text/plain", i.type)
        assertEquals(command, i.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("EXTRA_TEXT is the only extra", setOf(Intent.EXTRA_TEXT), i.extras!!.keySet())
        assertNull("no subject", i.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull("no title", i.getStringExtra(Intent.EXTRA_TITLE))
        assertNull("no data uri", i.data)
        assertNull("no clip", i.clipData)
        assertNull("no component: the user picks the target", i.component)
        assertEquals("no flags that grant anything", 0, i.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }
}
