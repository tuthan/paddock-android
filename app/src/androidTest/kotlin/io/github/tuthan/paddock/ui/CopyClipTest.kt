package io.github.tuthan.paddock.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 15 slice 3 on a device (API 26, 36, 37): the clip each kind makes, the real clipboard's round trip, the Compose wrapper, and C7's guard against the real
 * clipboard. The rule only keeps a window in front: from API 29 an app that has no focus reads an empty clipboard.
 */
class CopyClipTest {
    @get:Rule val rule = createComposeRule()

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cm get() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun marked(clip: ClipData?) = clip?.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE_KEY, false) == true
    private fun onMain(block: () -> Unit) = rule.runOnUiThread(block)

    @Test fun eachKindMakesItsClipMarkedExactlyAsItsSensitivitySays() {
        for (kind in CopyKind.entries) for (protect in listOf(true, false)) {
            val clip = clipFor(kind, "the text", protect)
            assertEquals("$kind protect=$protect", kind.marked(protect), marked(clip))
            assertEquals(kind.label, clip.description.label)
            assertEquals("the text", clip.getItemAt(0).text.toString())
        }
    }

    @Test fun aLabelFromTheRouteReplacesTheKindsOwn() {
        assertEquals("Paddock guarded answers registration (claude)", clipFor(CopyKind.GuardedSetup, "x", true, label = "Paddock guarded answers registration (claude)").description.label)
    }

    @Test fun theMarkKeepsAnExtraAlreadyOnTheClip() {
        val clip = ClipData.newPlainText("l", "t").also { it.description.extras = android.os.PersistableBundle().apply { putString("other", "kept") } }
        markSensitive(clip)
        assertEquals("kept", clip.description.extras?.getString("other"))
        assertTrue(marked(clip))
    }

    @Test fun copyTextRoundTripsThroughTheRealClipboardWithTheMark() {
        var ok = false
        onMain { ok = copyText(ctx, CopyKind.Output, "paddock-copy-test one\ntwo", protect = true) }
        assertTrue(ok)
        var clip: ClipData? = null
        onMain { clip = cm.primaryClip }
        assertEquals("paddock-copy-test one\ntwo", clip?.getItemAt(0)?.text?.toString())
        assertTrue("the extra survives the clipboard service", marked(clip))
        onMain { ok = copyText(ctx, CopyKind.Output, "paddock-copy-test plain", protect = false) }
        onMain { clip = cm.primaryClip }
        assertFalse("with the setting off nothing is marked", marked(clip))
    }

    @Test fun theWrapperMarksTheToolbarsCopyWhileProtectedAndPassesEverythingOn() = runBlocking {
        val seen = mutableListOf<ClipEntry?>()
        val inner = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = ClipEntry(ClipData.newPlainText("l", "from inner"))
            override suspend fun setClipEntry(clipEntry: ClipEntry?) { seen += clipEntry }
            override val nativeClipboard: ClipboardManager get() = cm
        }
        var protect = true
        val wrapped = SensitiveClipboard(inner) { protect }
        wrapped.setClipEntry(ClipEntry(ClipData.newPlainText("l", "selected")))
        assertTrue(marked(seen.last()?.clipData))
        protect = false // the setting changing while the view is open takes effect on the next copy
        wrapped.setClipEntry(ClipEntry(ClipData.newPlainText("l", "selected again")))
        assertFalse(marked(seen.last()?.clipData))
        wrapped.setClipEntry(null)
        assertNull(seen.last())
        assertEquals("from inner", wrapped.getClipEntry()?.clipData?.getItemAt(0)?.text.toString())
        assertEquals(cm, wrapped.nativeClipboard)
    }

    @Test fun clearClipEmptiesTheClipboard() {
        onMain { copyText(ctx, CopyKind.AlertSetup, "secret-topic") }
        onMain { clearClip(ctx) }
        var clip: ClipData? = null
        onMain { clip = cm.primaryClip }
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
        assertEquals("below API 28 the clip is replaced by an empty one, from 28 it is cleared (API ${Build.VERSION.SDK_INT})", "", text)
    }

    @Test fun theGuardClearsAnAlertCopyThatIsStillThereAndLeavesAnotherClipAlone() {
        val guard = AlertClipGuard.of(ctx)
        onMain { assertTrue(copyText(ctx, CopyKind.AlertSetup, "paddock-copy-test topic")); guard.arm() }
        onMain { guard.onFront() }
        var clip: ClipData? = null
        onMain { clip = cm.primaryClip }
        assertEquals("the alert copy is gone", "", clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty())

        onMain { assertTrue(copyText(ctx, CopyKind.SubscribeLink, "ntfy://paddock-copy-test")); guard.arm() }
        onMain { cm.setPrimaryClip(ClipData.newPlainText("somebody else", "another app's clip")) }
        onMain { guard.onFront() }
        onMain { clip = cm.primaryClip }
        assertNotNull(clip)
        assertEquals("another app's clip", clip?.getItemAt(0)?.text?.toString())
    }

    @Test fun anOutputCopyIsNeverClearedByTheGuard() {
        val guard = AlertClipGuard.of(ctx)
        onMain { assertTrue(copyText(ctx, CopyKind.Output, "paddock-copy-test output")) } // not armed: only the two alert copies are
        onMain { guard.onFront() }
        var clip: ClipData? = null
        onMain { clip = cm.primaryClip }
        assertEquals("paddock-copy-test output", clip?.getItemAt(0)?.text?.toString())
    }
}
