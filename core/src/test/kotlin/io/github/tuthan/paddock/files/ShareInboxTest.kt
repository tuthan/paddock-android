package io.github.tuthan.paddock.files

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShareInboxTest {
    private val discarded = CopyOnWriteArrayList<SharedItem>()
    private val inbox = ShareInbox { discarded += it }

    private fun snapshot(n: Int) = Snapshot(id = "snap-$n", name = "file-$n.png", mime = "image/png", size = 10L + n, sha256 = "0".repeat(64))
    private fun file(n: Int, text: String? = null) = SharedItem.File(snapshot(n), text)

    @Test fun nothingWaitsAtFirst() {
        assertNull(inbox.pending.value)
        assertNull(inbox.take())
        inbox.discard()
        assertTrue(discarded.isEmpty())
    }

    @Test fun anOfferedItemWaitsUntilTaken() {
        val item = file(1, text = "see this")
        inbox.offer(item)
        assertSame(item, inbox.pending.value)
        assertSame(item, inbox.take())
        assertNull(inbox.pending.value)
        assertNull(inbox.take())
    }

    @Test fun aNewItemReplacesTheOldOneAndTheOldOneIsDiscardedOnce() {
        val first = file(1)
        val second = file(2)
        inbox.offer(first)
        assertTrue(discarded.isEmpty(), "the first offer replaces nothing")
        inbox.offer(second)
        assertSame(second, inbox.pending.value)
        assertEquals(listOf<SharedItem>(first), discarded)
        assertSame(second, inbox.take())
        assertEquals(listOf<SharedItem>(first), discarded)
    }

    @Test fun aTextReplacesAFileAndTheFileIsDiscarded() {
        val first = file(1)
        inbox.offer(first)
        inbox.offer(SharedItem.Text("hello", removed = 2))
        assertEquals(SharedItem.Text("hello", 2), inbox.pending.value)
        assertEquals(listOf<SharedItem>(first), discarded)
    }

    @Test fun takeClearsWithoutDiscardingBecauseTheCallerOwnsTheItemNow() {
        inbox.offer(file(1))
        val taken = inbox.take()
        assertEquals(file(1), taken)
        assertNull(inbox.pending.value)
        assertTrue(discarded.isEmpty())
        // Offering after a take replaces nothing, so nothing is discarded either.
        inbox.offer(file(2))
        assertTrue(discarded.isEmpty())
    }

    @Test fun discardClearsAndHandsTheItemOver() {
        val item = file(1)
        inbox.offer(item)
        inbox.discard()
        assertNull(inbox.pending.value)
        assertEquals(listOf<SharedItem>(item), discarded)
        inbox.discard()
        assertEquals(1, discarded.size, "a second discard has nothing to hand over")
    }

    @Test fun discardingTheItemThePersonSawLeavesANewerOneThatTookItsPlace() {
        val seen = file(1)
        val newer = file(2)
        inbox.offer(seen)
        inbox.offer(newer)
        assertEquals(listOf<SharedItem>(seen), discarded, "the offer already let the first one go")
        assertFalse(inbox.discard(seen), "the item on the screen is no longer the one waiting")
        assertSame(newer, inbox.pending.value, "the newer item still waits")
        assertEquals(listOf<SharedItem>(seen), discarded, "and was not handed over")
        assertTrue(inbox.discard(newer))
        assertNull(inbox.pending.value)
        assertEquals(listOf<SharedItem>(seen, newer), discarded)
        assertFalse(inbox.discard(newer), "nothing waits, nothing is handed over again")
        assertEquals(2, discarded.size)
    }

    @Test fun takingTheItemThePersonChoseLeavesANewerOneThatTookItsPlace() {
        val seen = file(1)
        val newer = file(2)
        inbox.offer(seen)
        inbox.offer(newer)
        assertFalse(inbox.take(seen), "the item on the screen is no longer the one waiting, so it is not added to a draft")
        assertSame(newer, inbox.pending.value, "the newer item still waits")
        assertTrue(inbox.take(newer))
        assertNull(inbox.pending.value)
        assertEquals(listOf<SharedItem>(seen), discarded, "a taken item is not discarded: its file now belongs to the draft")
    }

    @Test fun offeringTheItemThatWaitsDoesNotDiscardIt() {
        val item = file(1)
        inbox.offer(item)
        inbox.offer(item)
        assertSame(item, inbox.pending.value)
        assertTrue(discarded.isEmpty(), "the snapshot is still in use")
    }

    @Test fun theDefaultDiscardDoesNothing() {
        val plain = ShareInbox()
        plain.offer(file(1))
        plain.offer(file(2))
        plain.discard()
        assertNull(plain.pending.value)
    }

    @Test fun anOfferStormLeavesExactlyOneWaitingAndEveryReplacedItemDiscardedExactlyOnce() {
        val threads = 8
        val perThread = 500
        val seen = ConcurrentHashMap<SharedItem, AtomicInteger>()
        val storm = ShareInbox { seen.getOrPut(it) { AtomicInteger() }.incrementAndGet() }
        val barrier = CyclicBarrier(threads)
        val all = (0 until threads * perThread).map { file(it) }
        val workers = (0 until threads).map { t ->
            Thread {
                barrier.await()
                for (i in 0 until perThread) storm.offer(all[t * perThread + i])
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        val waiting = checkNotNull(storm.pending.value) { "one item waits" }
        assertEquals(all.size - 1, seen.size, "every other item was replaced")
        assertTrue(seen.values.all { it.get() == 1 }, "each was discarded exactly once")
        assertTrue(waiting !in seen.keys, "the waiting item was not discarded")
        assertEquals(all.toSet(), seen.keys + waiting, "nothing was lost")
        // What waits is the user's to take; nothing more is discarded.
        assertEquals(waiting, storm.take())
        assertEquals(all.size - 1, seen.size)
    }

    @Test fun offersTakesAndDiscardsRacingNeverDiscardATakenItemOrDiscardOneTwice() {
        val rounds = 200
        val threads = 6
        val taken = ConcurrentHashMap.newKeySet<SharedItem>()
        val seen = ConcurrentHashMap<SharedItem, AtomicInteger>()
        val inbox = ShareInbox { seen.getOrPut(it) { AtomicInteger() }.incrementAndGet() }
        val barrier = CyclicBarrier(threads)
        val offered = CopyOnWriteArrayList<SharedItem>()
        val workers = (0 until threads).map { t ->
            Thread {
                barrier.await()
                for (i in 0 until rounds) {
                    val item = file(t * rounds + i)
                    offered += item
                    inbox.offer(item)
                    when (i % 3) {
                        0 -> inbox.take()?.let { taken += it }
                        1 -> inbox.discard()
                    }
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        val left = inbox.take()
        assertTrue(seen.values.all { it.get() == 1 }, "each item is handed to onDiscard at most once")
        assertTrue(taken.none { it in seen.keys }, "an item the caller took is never discarded")
        // Every item is accounted for: taken, discarded or still waiting, and no item is two of those.
        val accounted = taken + seen.keys + listOfNotNull(left)
        assertEquals(offered.toSet(), accounted.toSet())
        assertEquals(offered.size, taken.size + seen.size + (if (left != null) 1 else 0))
    }
}
