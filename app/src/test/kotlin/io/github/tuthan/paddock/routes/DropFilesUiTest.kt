package io.github.tuthan.paddock.routes

import io.github.tuthan.paddock.files.DropFolderCheck
import io.github.tuthan.paddock.files.RemoteFile
import io.github.tuthan.paddock.ui.screens.DropFilesUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the machine's page shows of a look at the drop folder: ten files at most, the machine's words cleaned, its refusal as a sentence. */
class DropFilesUiTest {
    private val now = 1_791_700_000_000L
    private fun ok(files: List<RemoteFile>, exists: Boolean = true, real: String = "/home/jdoe/Paddock") = DropFolderCheck.Result.Ok("~/Paddock", real, exists, files)

    @Test fun tenFilesAreListedAndTheRestIsCounted() {
        val files = List(13) { RemoteFile("f$it.txt", 100L + it, now / 1000 - 60) }
        val ui = dropFilesUi(ok(files), now) as DropFilesUi.Listed
        assertEquals(10, ui.rows.size)
        assertEquals(3, ui.more)
        assertEquals("f0.txt", ui.rows.first().name)
        assertTrue(ui.rows.first().detail, ui.rows.first().detail.contains("changed"))
    }

    @Test fun fewerThanTenHaveNothingLeftOver() {
        val ui = dropFilesUi(ok(listOf(RemoteFile("a.png", 2048, now / 1000))), now) as DropFilesUi.Listed
        assertEquals(1, ui.rows.size)
        assertEquals(0, ui.more)
        assertTrue(ui.exists)
    }

    @Test fun aFolderThatIsNotThereYetListsNothingAndSaysItDoesNotExist() {
        val ui = dropFilesUi(ok(emptyList(), exists = false), now) as DropFilesUi.Listed
        assertFalse(ui.exists)
        assertTrue(ui.rows.isEmpty())
    }

    @Test fun namesAndThePathFromTheMachineAreCleanedBeforeTheyAreShown() {
        val ui = dropFilesUi(ok(listOf(RemoteFile("a‮b\u001B[31m.txt", 1, now / 1000)), real = "/home/j‮doe/P"), now) as DropFilesUi.Listed
        assertTrue(ui.rows.single().name, ui.rows.single().name.none { it == '‮' || it == '\u001B' })
        assertTrue(ui.where, ui.where.none { it == '‮' })
    }

    @Test fun aProblemIsItsSentence() {
        assertEquals(DropFilesUi.Failed("That is the whole home folder."), dropFilesUi(DropFolderCheck.Result.Problem("That is the whole home folder."), now))
    }
}
