package io.github.tuthan.paddock.cli

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The shell script that ends herdr's observer with the SSH channel ([HerdrCli.OBSERVE_WATCH]), run for real: a stand-in for
 * the observer, and stdin closed the way sshd closes it when the channel goes. This is the behaviour that left one stray
 * `herdr terminal session observe` per closed Terminal view on an idle pane.
 */
class ObserveWatchTest {
    private val dir = Files.createTempDirectory("observe-watch").toFile()

    private fun start(inner: String): Process {
        assumeTrue(File("/bin/sh").canExecute())
        return ProcessBuilder(listOf("/bin/sh", "-c", HerdrCli.OBSERVE_WATCH, "paddock-observe", "/bin/sh", "-c", inner)).redirectErrorStream(true).start()
    }

    private fun waitFor(what: String, millis: Long = 8_000, cond: () -> Boolean) {
        val end = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < end) { if (cond()) return; Thread.sleep(25) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun theObserverEndsWhenStdinCloses() {
        val pidFile = File(dir, "observer.pid")
        val p = start("echo \$\$ > ${pidFile.absolutePath}; exec sleep 60")
        waitFor("the stand-in observer to start") { pidFile.exists() && pidFile.readText().isNotBlank() }
        val observer = pidFile.readText().trim().toLong()
        assertTrue(ProcessHandle.of(observer).map { it.isAlive }.orElse(false), "the observer should be running while the channel is open")
        p.outputStream.close() // what sshd does to the command's stdin when the channel is closed or the connection drops
        assertTrue(p.waitFor(8, TimeUnit.SECONDS), "the script should end with its stdin")
        waitFor("the observer to be gone") { !ProcessHandle.of(observer).map { it.isAlive }.orElse(false) }
    }

    @Test fun theScriptEndsWithTheObserverAndKeepsItsStatusAndOutputWithoutWaitingForStdin() {
        val p = start("echo frame; exit 7")
        assertTrue(p.waitFor(8, TimeUnit.SECONDS), "an observer that ended by itself must not wait for end of file")
        assertEquals(7, p.exitValue())
        assertEquals("frame\n", p.inputStream.readBytes().toString(Charsets.UTF_8))
        p.outputStream.close() // lets the guard's cat go
    }

    @Test fun theObserverNeverSeesTheChannelsInput() {
        // Input bytes are for the guard, not for herdr: a stand-in that tried to read would get end of file, not these.
        val p = start("read line; echo \"read:[\$line]\"")
        p.outputStream.write("hello\n".toByteArray()); p.outputStream.flush()
        assertTrue(p.waitFor(8, TimeUnit.SECONDS))
        assertEquals("read:[]\n", p.inputStream.readBytes().toString(Charsets.UTF_8))
        assertFalse(p.isAlive)
        p.outputStream.close()
    }
}
