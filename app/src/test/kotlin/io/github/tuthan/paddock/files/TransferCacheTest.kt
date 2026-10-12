package io.github.tuthan.paddock.files

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 10L * TransferCache.MAX_AGE_MILLIS
    private fun cache() = TransferCache(tmp.root) { now }

    private fun File.aged(millis: Long) = apply { setLastModified(now - millis) }
    private fun snapshot(c: TransferCache, id: String, age: Long): File = c.snapshotDir(id).also {
        File(it, "file.bin").apply { writeText("x") }.aged(age)
        it.aged(age)
    }

    @Test fun aReferencedSnapshotStaysForADay() {
        val c = cache()
        snapshot(c, "kept", TransferCache.MAX_AGE_MILLIS - 1000)
        assertEquals(0, c.sweep(setOf("kept")).snapshots)
        assertTrue(c.existingSnapshotDir("kept") != null)
    }

    @Test fun aSnapshotOlderThanADayGoesEvenWhenNamed() {
        val c = cache()
        snapshot(c, "old", TransferCache.MAX_AGE_MILLIS + 1000)
        assertEquals(1, c.sweep(setOf("old")).snapshots)
        assertNull(c.existingSnapshotDir("old"))
    }

    @Test fun anUnnamedSnapshotGoesOncePastTheGrace() {
        val c = cache()
        snapshot(c, "orphan", TransferCache.GRACE_MILLIS + 1000)
        assertEquals(1, c.sweep(emptySet()).snapshots)
        assertNull(c.existingSnapshotDir("orphan"))
    }

    @Test fun anIntakeThatIsWritingRightNowIsNotSweptUnderIt() {
        val c = cache()
        snapshot(c, "fresh", 1000)
        assertEquals(0, c.sweep(emptySet()).snapshots)
        assertTrue(c.existingSnapshotDir("fresh") != null)
    }

    @Test fun aCameraCaptureWhoseSnapshotNeverCameGoesPastTheGraceAndAFreshOneStays() {
        val c = cache()
        c.cameraFile("old").apply { writeText("x") }.aged(TransferCache.GRACE_MILLIS + 1000)
        c.cameraFile("new").apply { writeText("x") }.aged(1000)
        assertEquals(1, c.sweep(emptySet()).camera)
        assertFalse(c.cameraFile("old").exists())
        assertTrue(c.cameraFile("new").exists())
    }

    @Test fun fetchedFilesGoAtADayAndAnEmptyMachineFolderGoesWithThem() {
        val c = cache()
        val m = c.machineDir("m1")
        File(m, "a.txt").apply { writeText("x") }.aged(TransferCache.MAX_AGE_MILLIS + 1000)
        File(m, "b.txt").apply { writeText("x") }.aged(1000)
        assertEquals(1, c.sweep(emptySet()).fetched)
        assertFalse(File(m, "a.txt").exists())
        assertTrue(File(m, "b.txt").exists())
        File(m, "b.txt").aged(TransferCache.MAX_AGE_MILLIS + 1000)
        c.sweep(emptySet())
        assertFalse("an empty machine folder is removed", m.exists())
    }

    @Test fun forgettingAMachineDeletesItsFetchedFiles() {
        val c = cache()
        File(c.machineDir("m1"), "a.txt").writeText("x")
        File(c.machineDir("m2"), "b.txt").writeText("x")
        c.forgetMachine("m1")
        assertFalse(File(tmp.root, "transfers/m1").exists())
        assertTrue(File(tmp.root, "transfers/m2/b.txt").exists())
    }

    @Test fun deletingASnapshotRemovesItsFolder() {
        val c = cache()
        snapshot(c, "s", 0)
        c.deleteSnapshot("s")
        assertNull(c.existingSnapshotDir("s"))
    }

    @Test fun anIdThatIsNotOursNeverBecomesAPath() {
        val c = cache()
        for (bad in listOf("", "..", "../x", "a/b", "a\\b", ".hidden", "-x", "a".repeat(65), "a b", "a\u0000b")) {
            assertFalse("[$bad]", TransferCache.validId(bad))
            try { c.snapshotDir(bad); fail("snapshotDir accepted [$bad]") } catch (_: IllegalArgumentException) {}
            try { c.cameraFile(bad); fail("cameraFile accepted [$bad]") } catch (_: IllegalArgumentException) {}
            try { c.machineDir(bad); fail("machineDir accepted [$bad]") } catch (_: IllegalArgumentException) {}
            assertNull(c.existingSnapshotDir(bad))
            c.deleteSnapshot(bad)
            c.forgetMachine(bad)
        }
        assertTrue("nothing was created outside", tmp.root.list().isNullOrEmpty())
    }

    @Test fun theSweepLeavesAnythingThatDoesNotLookLikeOurs() {
        val c = cache()
        val odd = File(tmp.root, "incoming/.odd").apply { mkdirs() }
        val loose = File(tmp.root, "incoming/loose.txt").apply { writeText("x") }
        odd.aged(5 * TransferCache.MAX_AGE_MILLIS); loose.aged(5 * TransferCache.MAX_AGE_MILLIS)
        c.sweep(emptySet())
        assertTrue(odd.exists())
        assertTrue(loose.exists())
    }

    @Test fun aLinkInsideATreeIsRemovedNotFollowed() {
        val c = cache()
        val outside = tmp.newFolder("outside")
        val precious = File(outside, "precious.txt").apply { writeText("keep") }
        val dir = c.snapshotDir("linked")
        File(dir, "file.bin").writeText("x")
        val link = Files.createSymbolicLink(File(dir, "escape").toPath(), outside.toPath())
        val old = FileTime.fromMillis(now - TransferCache.MAX_AGE_MILLIS - 1000)
        Files.getFileAttributeView(link, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).setTimes(old, null, null)
        File(dir, "file.bin").aged(TransferCache.MAX_AGE_MILLIS + 1000)
        dir.aged(TransferCache.MAX_AGE_MILLIS + 1000)
        c.sweep(emptySet())
        assertNull(c.existingSnapshotDir("linked"))
        assertTrue("the link's target must survive", precious.exists())
    }

    // ---- slice 12: what Get brought ----

    @Test fun forgettingAMachineDeletesItsFetchedFilesAndNothingElse() {
        val c = cache()
        File(c.fetchFile("m1", "a", "one.txt").also { it.writeText("1") }.path).aged(1000)
        c.fetchFile("m2", "b", "two.txt").writeText("2")
        snapshot(c, "snap", 1000)
        c.forgetMachine("m1")
        assertFalse(File(tmp.root, "transfers/m1").exists())
        assertTrue("another machine's files stay", File(tmp.root, "transfers/m2/b/two.txt").exists())
        assertTrue("a snapshot stays", c.existingSnapshotDir("snap") != null)
        c.forgetMachine("../m2")
        assertTrue("a bad id names nothing", File(tmp.root, "transfers/m2/b/two.txt").exists())
    }

    @Test fun aFetchedFolderOlderThanADayIsSweptAndANewerOneStays() {
        val c = cache()
        c.fetchFile("m1", "old", "a.txt").writeText("a")
        c.fetchFile("m1", "new", "b.txt").writeText("b")
        File(tmp.root, "transfers/m1/old").aged(TransferCache.MAX_AGE_MILLIS + 1000)
        assertEquals(1, c.sweep(emptySet()).fetched)
        assertFalse(File(tmp.root, "transfers/m1/old").exists())
        assertTrue(File(tmp.root, "transfers/m1/new/b.txt").exists())
    }

    @Test fun deletingOneFetchTakesItsPartAndLeavesTheNeighbour() {
        val c = cache()
        c.fetchFile("m1", "a", "part.bin").writeText("half")
        c.fetchFile("m1", "b", "other.bin").writeText("whole")
        c.deleteFetched("m1", "a")
        assertFalse(File(tmp.root, "transfers/m1/a").exists())
        assertTrue(File(tmp.root, "transfers/m1/b/other.bin").exists())
        c.deleteFetched("m1", "../b")
        assertTrue("a bad id names nothing", File(tmp.root, "transfers/m1/b/other.bin").exists())
    }
}
