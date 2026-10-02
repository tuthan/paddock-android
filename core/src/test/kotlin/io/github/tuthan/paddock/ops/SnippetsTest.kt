package io.github.tuthan.paddock.ops

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class SnippetsTest {
    private fun done(c: Snippets.Change) = assertIs<Snippets.Change.Done>(c).items
    private fun refused(c: Snippets.Change) = assertIs<Snippets.Change.Refused>(c).reason

    @Test fun addTrimsAndAppends() {
        assertEquals(listOf("Continue", "Run the tests"), done(Snippets.add(listOf("Continue"), "  Run the tests \n")))
    }

    @Test fun anEmptyOrBlankSnippetIsRefused() {
        assertEquals("A snippet cannot be empty.", refused(Snippets.add(emptyList(), "   \n ")))
    }

    @Test fun aSnippetIsAtMost500CharactersAndSaysHowLongThisOneIs() {
        assertEquals(Snippets.MAX_LENGTH, done(Snippets.add(emptyList(), "x".repeat(500))).single().length)
        assertEquals("A snippet is at most 500 characters; this one is 501.", refused(Snippets.add(emptyList(), "x".repeat(501))))
    }

    @Test fun aRepeatIsRefusedButEditingToTheSameTextIsFine() {
        val list = listOf("a", "b")
        assertEquals("That snippet is already saved.", refused(Snippets.add(list, "b")))
        assertEquals("That snippet is already saved.", refused(Snippets.edit(list, 0, "b")))
        assertEquals(list, done(Snippets.edit(list, 1, "b")))
    }

    @Test fun fiftyAreKeptAndTheFiftyFirstIsRefusedWithTheWayOut() {
        val fifty = (1..50).map { "s$it" }
        assertEquals(50, done(Snippets.add(fifty.dropLast(1), "last")).size)
        assertEquals("You can keep 50 snippets. Remove one to add another.", refused(Snippets.add(fifty, "one more")))
        assertEquals(50, done(Snippets.edit(fifty, 3, "changed")).size, "editing at the cap is allowed")
    }

    @Test fun editAndRemoveAndMove() {
        val l = listOf("a", "b", "c")
        assertEquals(listOf("a", "x", "c"), done(Snippets.edit(l, 1, "x")))
        assertEquals("That snippet is gone.", refused(Snippets.edit(l, 9, "x")))
        assertEquals(listOf("a", "c"), Snippets.remove(l, 1))
        assertEquals(l, Snippets.remove(l, 7))
        assertEquals(listOf("b", "c", "a"), Snippets.move(l, 0, 2))
        assertEquals(l, Snippets.move(l, 0, 9))
    }

    @Test fun insertJoinsWithOneSpaceOrNone() {
        assertEquals("Continue", Snippets.insert("", "Continue"))
        assertEquals("Please Continue", Snippets.insert("Please", "Continue"))
        assertEquals("Please Continue", Snippets.insert("Please ", "Continue"))
        assertEquals("Line one\nContinue", Snippets.insert("Line one\n", "Continue"))
    }

    @Test fun normalizeCleansWhatIsStored() {
        val noisy = listOf(" a ", "", "a", "x".repeat(501), "b") + (1..60).map { "n$it" }
        val n = Snippets.normalize(noisy)
        assertEquals(listOf("a", "b"), n.take(2))
        assertEquals(50, n.size)
    }

    @Test fun theFileStoreRoundTripsAndAnUnreadableFileIsEmpty() = runBlocking {
        val file = Files.createTempDirectory("snip").toFile().resolve("snippets.json")
        assertEquals(emptyList(), FileSnippetStore(file).load())
        FileSnippetStore(file).save(listOf("Continue", "Run the tests"))
        assertEquals(listOf("Continue", "Run the tests"), FileSnippetStore(file).load())
        file.writeText("{ not json")
        assertEquals(emptyList(), FileSnippetStore(file).load())
        file.writeText("""{"items": ["ok", "ok", ""], "later": 1}""")
        assertEquals(listOf("ok"), FileSnippetStore(file).load())
    }
}
