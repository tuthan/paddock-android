package io.github.tuthan.paddock.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** X5, AC-15.29: what the sheet says about an APK, from the facts the platform gave. */
class ApkNotesTest {
    private val own = "io.github.tuthan.paddock"
    private val signer = "a".repeat(64)
    private val other = "b".repeat(64)
    private fun apk(pkg: String = "com.example.tool", signers: List<String> = listOf(signer), name: String? = "1.2") = ApkInfo(pkg, name, 34, signers)

    @Test fun aReadableApkShowsItsPackageVersionAndSignerAndWhereToInstallFrom() {
        val lines = ApkNotes.lines(apk(), own, listOf(signer))
        assertEquals(listOf("Package com.example.tool", "Version 1.2 (34)", "Signer SHA-256 $signer", ApkNotes.WHERE), lines)
    }

    @Test fun anUnsignedPackageIsNamedAsUnsignedAndShowsNoSigner() {
        val lines = ApkNotes.lines(apk(signers = emptyList()), own, listOf(signer))
        assertTrue(ApkNotes.UNSIGNED in lines)
        assertFalse(lines.any { it.startsWith("Signer") })
    }

    @Test fun anUnreadableFileSaysSoAndStillSaysWhereToInstallFrom() = assertEquals(listOf(ApkNotes.UNREADABLE, ApkNotes.WHERE), ApkNotes.lines(null, own, listOf(signer)))

    @Test fun paddocksOwnPackageWithAnotherSignerWarnsThatInstallingMeansUninstallingFirst() {
        val lines = ApkNotes.lines(apk(pkg = own, signers = listOf(other)), own, listOf(signer))
        assertTrue(ApkNotes.OTHER_SIGNER in lines)
        assertTrue("forgets this phone's key" in ApkNotes.OTHER_SIGNER)
        assertFalse(ApkNotes.SAME_SIGNER in lines)
    }

    @Test fun paddocksOwnPackageWithTheSameSignerDoesNotWarn() {
        val lines = ApkNotes.lines(apk(pkg = own, signers = listOf(signer)), own, listOf(signer))
        assertTrue(ApkNotes.SAME_SIGNER in lines)
        assertFalse(ApkNotes.OTHER_SIGNER in lines)
    }

    @Test fun anotherPackageNeverGetsTheOwnPackageLine() {
        val lines = ApkNotes.lines(apk(pkg = "io.github.tuthan.paddock.evil", signers = listOf(other)), own, listOf(signer))
        assertFalse(ApkNotes.OTHER_SIGNER in lines)
        assertFalse(ApkNotes.SAME_SIGNER in lines)
    }

    @Test fun whenPaddocksOwnSignerCouldNotBeReadNothingIsClaimedAboutTheSigner() {
        val lines = ApkNotes.lines(apk(pkg = own, signers = listOf(other)), own, emptyList())
        assertFalse(ApkNotes.OTHER_SIGNER in lines)
        assertFalse(ApkNotes.SAME_SIGNER in lines)
    }

    @Test fun theFilesOwnTextIsShownCleanedAndCut() {
        val lines = ApkNotes.lines(apk(pkg = "com.‮example", name = "v​1".repeat(100)), own, listOf(signer))
        assertTrue(lines.none { l -> l.any { it == '‮' || it == '​' } })
        assertTrue(lines[1].length <= "Version ".length + ApkInfo.SHOWN + " (34)".length)
    }

    @Test fun twoSignersAreBothShownAndCompareAsASet() {
        val two = apk(pkg = own, signers = listOf(signer, other))
        val lines = ApkNotes.lines(two, own, listOf(other, signer))
        assertEquals(2, lines.count { it.startsWith("Signer SHA-256") })
        assertTrue(ApkNotes.SAME_SIGNER in lines)
    }
}
