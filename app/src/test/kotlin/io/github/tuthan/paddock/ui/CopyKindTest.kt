package io.github.tuthan.paddock.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 15, C4 and C7: every copy the app makes says whether it is a secret, in one table, and nothing writes the clipboard outside `ui/Copy.kt`.
 * The scan is crude on purpose, like the button roles' one: it fails the moment someone writes a clip by hand and skips the mark.
 */
class CopyKindTest {
    private val main = File("src/main/kotlin")

    @Test fun theTableSaysWhichCopiesAreSecrets() {
        assertEquals(Sensitivity.WhileProtected, CopyKind.Output.sensitivity)
        for (k in listOf(CopyKind.PublicKey, CopyKind.AuthorizeCommand, CopyKind.WakeCommand, CopyKind.GuardedSetup, CopyKind.MachinePath)) assertEquals("$k", Sensitivity.Never, k.sensitivity)
        // The ntfy topic and a self-hosted server's token: secrets whatever the setting says.
        for (k in listOf(CopyKind.AlertSetup, CopyKind.SubscribeLink)) assertEquals("$k", Sensitivity.Always, k.sensitivity)
        assertEquals("a new kind must be added to this test with its sensitivity", 8, CopyKind.entries.size)
    }

    @Test fun outputIsMarkedExactlyWhileTheSettingIsOn() {
        assertTrue(CopyKind.Output.marked(protect = true))
        assertFalse(CopyKind.Output.marked(protect = false))
    }

    @Test fun theAlertCopiesAreMarkedWhateverTheSettingSaysAndThePublicOnesNever() {
        for (protect in listOf(true, false)) {
            assertTrue(CopyKind.AlertSetup.marked(protect))
            assertTrue(CopyKind.SubscribeLink.marked(protect))
            for (k in listOf(CopyKind.PublicKey, CopyKind.AuthorizeCommand, CopyKind.WakeCommand, CopyKind.GuardedSetup, CopyKind.MachinePath)) assertFalse("$k", k.marked(protect))
        }
    }

    @Test fun everyKindHasItsOwnLabelExceptWhereTheRouteNamesTheCopy() {
        assertEquals(CopyKind.entries.size, CopyKind.entries.map { it.label }.toSet().size)
        assertEquals("Paddock alert relay setup", CopyKind.AlertSetup.label)
        assertEquals("ntfy subscribe link", CopyKind.SubscribeLink.label)
    }

    @Test fun theMarkIsTheKeyThatEveryApiLevelAndKeyboardReads() {
        assertEquals("android.content.extra.IS_SENSITIVE", EXTRA_IS_SENSITIVE_KEY)
    }

    @Test fun noClipboardWriteLivesOutsideTheHelper() {
        val writes = listOf("setPrimaryClip(", "ClipData.newPlainText(", "ClipData.newRawUri(", "ClipData.newUri(", "ClipData.newIntent(", "clearPrimaryClip(", "setClipEntry(")
        // A share intent carries a `ClipData` so the receiving app gets its read grant for the one file (Phase 15, SEC-5). That clip goes to `Intent.setClipData` and is
        // never given to a clipboard: the exemption is that one call in that one file, and the file may not touch a clipboard at all (the next test).
        val exempt = mapOf("Handoff.kt" to setOf("ClipData.newRawUri("))
        val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Copy.kt" }.flatMap { f ->
            val text = f.readText()
            writes.filter { it in text && it !in exempt[f.name].orEmpty() }.map { "${f.name}: $it" }
        }.toList()
        assertTrue("clipboard writes outside ui/Copy.kt: $offenders", offenders.isEmpty())
    }

    @Test fun theShareIntentsClipIsNeverNearAClipboard() {
        val handoff = main.walkTopDown().single { it.isFile && it.name == "Handoff.kt" }.readText()
        for (word in listOf("ClipboardManager", "CLIPBOARD_SERVICE", "setPrimaryClip", "setClipEntry", "primaryClip")) assertTrue("Handoff.kt mentions $word", word !in handoff)
        assertTrue("its one clip goes to the intent", Regex("""clipData\s*=\s*ClipData\.newRawUri\(""").containsMatchIn(handoff))
    }
}
