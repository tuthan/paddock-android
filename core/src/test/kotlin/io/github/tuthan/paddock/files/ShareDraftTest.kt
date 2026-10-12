package io.github.tuthan.paddock.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareDraftTest {
    @Test fun anEmptyOrBlankDraftBecomesTheSharedText() {
        assertEquals("https://example.org/a", ShareDraft.merge("", "https://example.org/a"))
        assertEquals("https://example.org/a", ShareDraft.merge("  \n ", "https://example.org/a"))
    }

    @Test fun aDraftWithWordsKeepsThemAndTheSharedTextFollowsAfterABlankLine() {
        assertEquals("look at this\n\nhttps://example.org/a", ShareDraft.merge("look at this", "https://example.org/a"))
        assertEquals("look at this\n\nhttps://example.org/a", ShareDraft.merge("look at this \n\n", "https://example.org/a"), "trailing space and lines of the draft do not pile up")
    }

    @Test fun theSharedTextKeepsItsOwnLineBreaks() {
        assertEquals("a\n\nline 1\nline 2\n\tindented", ShareDraft.merge("a", "line 1\nline 2\n\tindented"))
    }

    @Test fun twoTextsThatTogetherPassTheLimitAreNotJoined() {
        assertNull(ShareDraft.merge("a".repeat(60), "b".repeat(50), max = 100))
        assertEquals(60 + 2 + 38, ShareDraft.merge("a".repeat(60), "b".repeat(38), max = 100)!!.length, "exactly at the limit fits")
        assertNull(ShareDraft.merge("a".repeat(60), "b".repeat(39), max = 100), "one over does not")
    }

    @Test fun aSharedTextAloneIsHeldToTheLimitToo() {
        assertEquals("b".repeat(100), ShareDraft.merge("", "b".repeat(100), max = 100))
        assertNull(ShareDraft.merge("", "b".repeat(101), max = 100))
    }
}
