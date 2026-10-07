package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeSession.Companion.result
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The icon's rules: what `uname -s` maps to, that a pick is never overwritten, that an old profiles file loads, and the rename rules. */
class HostOsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun profile(id: String = "a", name: String = "A", os: HostOs? = null, detected: HostOs? = null) =
        HostProfile(id, name, "h", user = "u", os = os, detectedOs = detected)

    @Test fun unameMapsToTheThreeKindsAndNothingElseIsGuessed() {
        assertEquals(HostOs.Linux, HostOs.fromUname("Linux\n"))
        assertEquals(HostOs.Mac, HostOs.fromUname("Darwin\n"))
        for (w in listOf("MINGW64_NT-10.0-22631", "MSYS_NT-10.0", "CYGWIN_NT-10.0")) assertEquals(HostOs.Windows, HostOs.fromUname("$w\n"), w)
        for (w in listOf("", "FreeBSD", "SunOS", "linux", "Linuxish", "Darwin Kernel", "not a uname")) assertNull(HostOs.fromUname(w), "'$w'")
    }

    @Test fun aBannerBeforeTheAnswerDoesNotHideIt() {
        // A login shell may print a banner; the answer is the last line.
        assertEquals(HostOs.Linux, HostOs.fromUname("Welcome to the box\nLinux\n"))
    }

    @Test fun theProbeAsksOneConstantCommandAndReadsItsAnswer() = runBlocking<Unit> {
        val s = FakeSession(onExec = { _, _ -> result(0, "Darwin\n") })
        assertEquals(HostOs.Mac, HostOsProbe.read(s))
        assertEquals(listOf("sh", "-c", HostOsProbe.COMMAND), s.execs.single().first)
    }

    @Test fun aMachineThatCannotAnswerIsNotKnownAndNeverAFailure() = runBlocking<Unit> {
        assertNull(HostOsProbe.read(FakeSession(onExec = { _, _ -> result(127, "", "uname: not found") })), "exit status")
        assertNull(HostOsProbe.read(FakeSession(onExec = { _, _ -> result(0, "") })), "silence")
        assertNull(HostOsProbe.read(FakeSession(onExec = { _, _ -> throw java.io.IOException("channel closed") })), "a channel that fails")
    }

    @Test fun theUsersPickBeatsWhatTheMachineSaidAndNeitherIsRequired() {
        assertNull(profile().shownOs)
        assertEquals(HostOs.Linux, profile(detected = HostOs.Linux).shownOs)
        assertEquals(HostOs.Windows, profile(os = HostOs.Windows, detected = HostOs.Linux).shownOs)
    }

    @Test fun aProfilesFileWrittenBeforeTheIconLoadsAndTheIconSurvivesARewrite() = runBlocking<Unit> {
        val f = File(tmp.root, "p.json").apply { writeText("""{"version":1,"profiles":[{"id":"a","name":"A","host":"h","user":"u"}]}""") }
        val store = FileHostProfileStore(f)
        assertNull(store.get("a")!!.shownOs)
        store.update("a") { it.copy(detectedOs = HostOs.Mac) }
        store.update("a") { it.copy(os = HostOs.Linux) }
        store.update("a") { it.copy(detectedOs = HostOs.Windows) } // a later reading never replaces the pick
        val again = FileHostProfileStore(f).get("a")!!
        assertEquals(HostOs.Linux, again.os); assertEquals(HostOs.Windows, again.detectedOs); assertEquals(HostOs.Linux, again.shownOs)
    }

    @Test fun theRosterAndTheChipsCarryTheShownIcon() {
        val all = listOf(profile("a", "Alpha", detected = HostOs.Linux), profile("b", "Beta", os = HostOs.Mac, detected = HostOs.Linux), profile("c", "Gamma"))
        val rows = MachineRoster.rows(all, watchedId = "a", chosenId = "a")
        assertEquals(listOf(HostOs.Linux, HostOs.Mac, null), rows.map { it.os })
        assertEquals(listOf(HostOs.Linux, HostOs.Mac, null), MachineRoster.chips(rows, switchLocked = false).map { it.os })
    }

    // ---- the name ----------------------------------------------------------------------------------------------------------------

    @Test fun aNameIsTrimmedAndAcceptedWhenItIsOneThingAndNotTaken() {
        assertEquals(MachineName.Result.Ok("Studio"), MachineName.check("  Studio  ", listOf("Laptop")))
        assertEquals(MachineName.Result.Ok("a".repeat(60)), MachineName.check("a".repeat(60), emptyList()))
    }

    @Test fun anEmptyLongOrOddNameIsRefusedWithWordsAndNothingElseIsSaid() {
        assertEquals(MachineName.Result.Empty, MachineName.check("   ", emptyList()))
        assertEquals(MachineName.Result.TooLong, MachineName.check("a".repeat(61), emptyList()))
        assertEquals(MachineName.Result.BadCharacter, MachineName.check("two\nlines", emptyList()))
        assertEquals(MachineName.Result.BadCharacter, MachineName.check("tab\there", emptyList()))
        assertEquals(MachineName.Result.Taken, MachineName.check("LAPTOP", listOf("Laptop")), "case is not a difference")
        assertNull(MachineName.message(MachineName.Result.Ok("x")))
        for (r in listOf(MachineName.Result.Empty, MachineName.Result.TooLong, MachineName.Result.BadCharacter, MachineName.Result.Taken)) assertTrue(MachineName.message(r)!!.isNotBlank())
    }

    @Test fun whatTheRuleAcceptsTheProfileAccepts() {
        val ok = (MachineName.check("  Build box 2  ", emptyList()) as MachineName.Result.Ok).name
        assertEquals("Build box 2", profile(name = ok).name)
        assertFailsWith<IllegalArgumentException> { profile(name = "a".repeat(61)) }
    }

    @Test fun theCopyNamesWhereTheIconCameFrom() {
        assertEquals("Linux (your pick)", MachineCopy.osFact(HostOs.Linux, HostOs.Mac))
        assertEquals("macOS (read from the machine)", MachineCopy.osFact(null, HostOs.Mac))
        assertEquals("Not known yet", MachineCopy.osFact(null, null))
        assertEquals("Automatic", MachineCopy.osAuto(null)); assertEquals("Automatic (Windows)", MachineCopy.osAuto(HostOs.Windows))
        assertNull(MachineCopy.osSpoken(null)); assertEquals("macOS machine", MachineCopy.osSpoken(HostOs.Mac))
    }
}
