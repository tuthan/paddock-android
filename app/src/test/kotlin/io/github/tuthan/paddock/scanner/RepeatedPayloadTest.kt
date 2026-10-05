package io.github.tuthan.paddock.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedPayloadTest {
    private val second = 1_000_000_000L

    @Test fun aCodeLeftInViewIsReportedOnceNoMatterHowOftenItIsRead() {
        val r = RepeatedPayload(3 * second)
        assertTrue(r.isNews("https://example.com/menu", 0))
        // Read every 150 ms for a minute, the way the camera does: never news again.
        for (i in 1..400) assertFalse("reading $i", r.isNews("https://example.com/menu", i * 150_000_000L))
    }

    @Test fun anotherCodeIsNewAndTheFirstIsNewAgainAfterItWasOutOfSight() {
        val r = RepeatedPayload(3 * second)
        assertTrue(r.isNews("a", 0))
        assertTrue(r.isNews("b", second))
        assertTrue("a came back right after b", r.isNews("a", 2 * second))
        assertFalse(r.isNews("a", 3 * second))
        assertTrue("out of sight for more than the window", r.isNews("a", 3 * second + 3 * second + 1))
    }

    @Test fun theWindowIsKeptOpenByEachSightingNotCountedFromTheFirst() {
        val r = RepeatedPayload(3 * second)
        assertTrue(r.isNews("a", 0))
        assertFalse(r.isNews("a", 2 * second))
        assertFalse("2.9 s after the last sighting, 4.9 s after the first", r.isNews("a", 4_900_000_000L))
    }
}
