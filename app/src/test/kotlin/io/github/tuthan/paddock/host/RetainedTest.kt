package io.github.tuthan.paddock.host

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = CopyOnWriteArrayList<String>()
    @After fun tearDown() { scope.cancel() }
    private fun retained(grace: Long = 150) = Retained<String>(scope, grace) { closed += it }

    @Test fun theSameKeyGetsTheSameItem() {
        val r = retained(); var made = 0
        val a = r.acquire("t1") { made++; "item" }; val b = r.acquire("t1") { made++; "other" }
        assertSame(a, b); assertEquals(1, made); assertTrue(closed.isEmpty())
    }

    @Test fun anotherKeyClosesTheFirstItem() {
        val r = retained(); r.acquire("t1") { "one" }; val two = r.acquire("t2") { "two" }
        assertEquals(listOf("one"), closed.toList()); assertEquals("two", two)
    }

    @Test fun leavingForGoodClosesAtOnce() {
        val r = retained(); val a = r.acquire("t1") { "one" }
        r.release(a, recreating = false)
        assertEquals(listOf("one"), closed.toList())
    }

    @Test fun aRecreationKeepsTheItemForTheNextAcquire() {
        val r = retained(grace = 400); val a = r.acquire("t1") { "one" }
        r.release(a, recreating = true)
        assertTrue(closed.isEmpty())
        assertSame(a, r.acquire("t1") { "fresh" })
        Thread.sleep(600)
        assertTrue("the grace was cancelled by the acquire", closed.isEmpty())
    }

    @Test fun aRecreationThatNeverComesBackClosesAfterTheGrace() {
        val r = retained(grace = 100); val a = r.acquire("t1") { "one" }
        r.release(a, recreating = true)
        val end = System.currentTimeMillis() + 3000
        while (closed.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(listOf("one"), closed.toList())
    }

    @Test fun aStaleReleaseChangesNothing() {
        val r = retained(); val a = r.acquire("t1") { "one" }; val b = r.acquire("t2") { "two" }
        r.release(a, recreating = false)
        assertEquals(listOf("one"), closed.toList()); assertTrue(b !== a)
    }

    @Test fun firstUseIsTrueOncePerItem() {
        val r = retained(); val a = r.acquire("t1") { "one" }
        assertTrue(r.firstUse(a)); assertFalse(r.firstUse(a))
        r.acquire("t1") { "x" }; assertFalse("still the same item after a re-create", r.firstUse(a))
        val b = r.acquire("t2") { "two" }; assertTrue(r.firstUse(b))
    }
}
