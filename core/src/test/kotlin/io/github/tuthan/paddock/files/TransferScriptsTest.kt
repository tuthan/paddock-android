package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.cli.argvToCommand
import io.github.tuthan.paddock.integration.LocalProcessSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The host scripts of Part B under a real `/bin/sh`, through [LocalProcessSession] (which also refuses an argv `argvToCommand` would), in a temporary
 * HOME, against real folders, real links and real git (AC-15.18, 15.19, 15.26, 15.36). Hostile inputs are built here; nothing real is read or written.
 * A refusal test compares the whole temporary tree before and after, down to the folders' modification times, so "before any file is touched" is exact.
 * No assertion message holds a name or a content.
 */
class TransferScriptsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var home: File
    private val cap = 1_000_000L

    @Before fun layout() {
        root = tmp.root.canonicalFile
        home = File(root, "home").also { it.mkdirs() }
    }

    // ---- running a script -------------------------------------------------------------------------------------------------

    private class Ran(val exit: Int, val stdout: ByteArray)

    private fun session(path: String? = null, extra: Map<String, String> = emptyMap()) =
        LocalProcessSession(buildMap { put("HOME", home.path); if (path != null) put("PATH", path); putAll(extra) })

    /** Runs [argv] with [input] on stdin to its end, as the app does. A script that refuses and leaves before reading everything is not an error here. */
    private fun run(argv: List<String>, input: ByteArray, s: LocalProcessSession = session()): Ran = runBlocking {
        withTimeout(30.seconds) {
            val ch = s.openStream(argv)
            try {
                val out = ByteArrayOutputStream()
                val reader = launch(Dispatchers.IO) { ch.stdout.collect { out.write(it) } }
                try { ch.write(input) } catch (_: IOException) { }
                runCatching { ch.closeStdin() }
                val exit = ch.awaitExit()
                reader.join()
                Ran(exit, out.toByteArray())
            } finally { ch.close() }
        }
    }

    private fun header(folder: String, name: String, n: Long) = "$folder\n$name\n$n\n"
    private fun body(n: Int, seed: Int = 7) = ByteArray(n) { (it * 31 + seed).toByte() }

    private fun put(folder: File, name: String, content: ByteArray, declared: Long = content.size.toLong(), s: LocalProcessSession = session()) =
        run(TransferScripts.putArgv(cap), header(folder.path, name, declared).toByteArray() + content, s)

    private fun putRaw(head: String, content: ByteArray = body(10), s: LocalProcessSession = session()) = run(TransferScripts.putArgv(cap), head.toByteArray() + content, s)

    private fun drop(folder: String, name: String, content: ByteArray, s: LocalProcessSession = session()) =
        run(TransferScripts.dropArgv(cap), header(folder, name, content.size.toLong()).toByteArray() + content, s)

    private fun project(name: String = "proj") = File(root, name).also { it.mkdirs() }
    private fun inboxOf(folder: File) = File(folder, ".paddock/inbox")
    private fun parts(dir: File) = dir.list().orEmpty().filter { ".part." in it }
    private fun mode(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath(), LinkOption.NOFOLLOW_LINKS))
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Every entry under [dir] (links not followed) with its kind, size and modification time: a folder's time moves when anything is created or removed in it. */
    private fun tree(dir: File): List<String> = Files.walk(dir.toPath()).use { s ->
        s.map { p ->
            val a = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            "${dir.toPath().relativize(p)}|${if (a.isSymbolicLink) "l" else if (a.isDirectory) "d" else "f"}|${a.size()}|${a.lastModifiedTime().to(TimeUnit.NANOSECONDS)}"
        }.sorted().toList()
    }

    private fun realTool(name: String): String = (System.getenv("PATH").orEmpty().split(':') + listOf("/usr/bin", "/bin")).map { File(it, name) }.first { it.isFile && it.canExecute() }.path

    private fun notRoot() = assumeTrue("a root account owns everything", System.getProperty("user.name") != "root")

    private val fifos = mutableListOf<File>()

    /**
     * A named pipe nobody reads. A script that wrongly opens it would block on it for good and hang the test, so a writer waits at the other end: an open
     * for reading then returns at once with nothing to read, and the test fails on what the script said instead. [releaseFifos] lets the writer go.
     */
    private fun fifo(f: File): File {
        ProcessBuilder(realTool("mkfifo"), f.path).start().waitFor()
        fifos += f
        Thread { runCatching { java.io.FileOutputStream(f).close() } }.also { it.isDaemon = true; it.start() }
        return f
    }

    @After fun releaseFifos() { fifos.forEach { f -> runCatching { java.io.RandomAccessFile(f, "rw").close() } }; fifos.clear() }

    private fun expectRefused(r: Ran, why: TransferRefusal) {
        assertEquals(why.exit, r.exit, "expected ${why.name}")
        assertEquals(0, r.stdout.size, "a write says nothing")
    }

    // ---- put: what lands, and how -----------------------------------------------------------------------------------------

    @Test fun `a put lands whole with mode 0600 in an inbox of 0700, says nothing, and a folder with a space works`() {
        val folder = project("my project")
        // The content starts as a header would and holds line breaks and NULs: read takes the header a byte at a time, so head gets exactly the file.
        val content = "\n\n5\nXYZ\n".toByteArray() + body(300_000)
        val r = put(folder, "shot.png", content)
        assertEquals(0, r.exit)
        assertEquals(0, r.stdout.size)
        val file = File(inboxOf(folder), "shot.png")
        assertContentEquals(content, file.readBytes())
        assertEquals("rw-------", mode(file))
        assertEquals("rwx------", mode(inboxOf(folder)))
        assertEquals("rwx------", mode(File(folder, ".paddock")))
        assertEquals(listOf("shot.png"), inboxOf(folder).list().orEmpty().toList(), "no part is left")
    }

    @Test fun `a name of 100 characters is the longest and a second put with the next name goes beside it`() {
        val folder = project()
        val longest = "a" + "b".repeat(99)
        assertEquals(0, put(folder, longest, body(5)).exit)
        assertEquals(0, put(folder, "x.a-b_c.txt", body(6)).exit)
        assertEquals(listOf(longest, "x.a-b_c.txt").sorted(), inboxOf(folder).list().orEmpty().sorted())
    }

    @Test fun `each refusal has its own exit status and leaves no part`() {
        notRoot()
        val folder = project()
        File(folder, ".paddock/inbox").mkdirs()
        File(inboxOf(folder), "taken.txt").writeText("mine")
        val cases = listOf(
            put(folder, "taken.txt", body(5)) to TransferRefusal.Exists,
            put(folder, "short.bin", body(4), declared = 10) to TransferRefusal.Truncated,
            put(folder, "big.bin", body(5), declared = cap + 1) to TransferRefusal.OverCap,
            putRaw("${folder.path}\nbad/name\n5\n") to TransferRefusal.BadName,
            putRaw("${folder.path}\nok.bin\nfive\n") to TransferRefusal.BadCount,
            putRaw("relative\nok.bin\n5\n") to TransferRefusal.BadPath,
            putRaw("${File(folder, "taken.txt").path}\nok.bin\n5\n") to TransferRefusal.NotDirectory,
            putRaw("/usr/lib\nok.bin\n5\n") to TransferRefusal.NotYours,
        )
        for ((r, why) in cases) expectRefused(r, why)
        assertEquals("mine", File(inboxOf(folder), "taken.txt").readText(), "an existing name is never overwritten")
        assertEquals(listOf("taken.txt"), inboxOf(folder).list().orEmpty().toList(), "and a refusal leaves no part")
        assertEquals(cases.map { it.second.exit }.toSet().size, cases.size, "each refusal has an exit status of its own")
    }

    @Test fun `a folder or an inbox that cannot be written is unwritable and not a half-made one`() {
        notRoot()
        val locked = project("locked").also { it.setWritable(false) }
        try {
            expectRefused(put(locked, "a.bin", body(5)), TransferRefusal.Unwritable)
            assertFalse(File(locked, ".paddock").exists())
        } finally { locked.setWritable(true) }
        val folder = project("closed-inbox")
        assertEquals(0, put(folder, "a.bin", body(5)).exit)
        inboxOf(folder).setWritable(false)
        try { expectRefused(put(folder, "b.bin", body(5)), TransferRefusal.Unwritable) } finally { inboxOf(folder).setWritable(true) }
        assertEquals(listOf("a.bin"), inboxOf(folder).list().orEmpty().toList())
    }

    @Test fun `a put into a folder without a gitignore writes none into a paddock that was already there and not made by it`() {
        val folder = project()
        File(folder, ".paddock").mkdirs()
        assertEquals(0, put(folder, "a.bin", body(5)).exit)
        assertFalse(File(folder, ".paddock/.gitignore").exists(), "only a .paddock this script created gets one (X1)")

        val mine = project("mine")
        File(mine, ".paddock").mkdirs()
        File(mine, ".paddock/.gitignore").writeText("# the user's own\nexclude-me/\n")
        assertEquals(0, put(mine, "a.bin", body(5)).exit)
        assertEquals("# the user's own\nexclude-me/\n", File(mine, ".paddock/.gitignore").readText())
    }

    @Test fun `the gitignore the script writes is star and private`() {
        val folder = project()
        assertEquals(0, put(folder, "a.bin", body(5)).exit)
        val ignore = File(folder, ".paddock/.gitignore")
        assertEquals("*\n", ignore.readText())
        assertEquals("rw-------", mode(ignore), "umask 077 holds for every file the script makes")
    }

    // ---- races, stalls, cuts ----------------------------------------------------------------------------------------------

    @Test fun `two puts on one name at once publish one whole file and refuse the other as existing, with no part left`() {
        val folder = project()
        repeat(5) { round ->
            val name = "race-$round.bin"
            val a = body(200_000, seed = 1)
            val b = body(200_000, seed = 2)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures = listOf(a, b).map { content -> pool.submit<Ran> { start.await(); put(folder, name, content) } }
                start.countDown()
                val exits = futures.map { it.get(60, TimeUnit.SECONDS).exit }.sorted()
                assertEquals(listOf(0, TransferRefusal.Exists.exit), exits)
            } finally { pool.shutdownNow() }
            val published = File(inboxOf(folder), name).readBytes()
            assertTrue(published.contentEquals(a) || published.contentEquals(b), "the file is one attempt's, whole")
            assertEquals(emptyList(), parts(inboxOf(folder)))
        }
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!cond()) { check(System.nanoTime() < end) { "timed out waiting for $what" }; Thread.sleep(10) }
    }

    @Test fun `a stalled attempt and a retry, the retry wins and the stalled one is refused as short with its part removed once its input ends`() {
        val folder = project()
        runBlocking {
            val stalled = session().openStream(TransferScripts.putArgv(cap))
            stalled.write(header(folder.path, "same.bin", 10).toByteArray() + body(4))
            waitFor("the stalled attempt's part") { parts(inboxOf(folder)).size == 1 }
            val part = File(inboxOf(folder), parts(inboxOf(folder)).single())
            assertEquals("rw-------", mode(part), "a part is private")
            assertTrue(part.name.startsWith(".same.bin.part."), "and named so the sweep can find it")

            val retry = body(10, seed = 9)
            assertEquals(0, put(folder, "same.bin", retry).exit)
            assertContentEquals(retry, File(inboxOf(folder), "same.bin").readBytes())

            stalled.closeStdin()
            assertEquals(TransferRefusal.Truncated.exit, stalled.awaitExit(), "the end of its input is short, whatever else is there")
            stalled.close()
            assertContentEquals(retry, File(inboxOf(folder), "same.bin").readBytes(), "the stalled attempt never replaced it")
            assertEquals(emptyList(), parts(inboxOf(folder)))
        }
    }

    @Test fun `a stream closed in the middle leaves no part, and a name not yet given stays free`() {
        val folder = project()
        runBlocking {
            val cut = session().openStream(TransferScripts.putArgv(cap))
            cut.write(header(folder.path, "cut.bin", 100).toByteArray() + body(40))
            waitFor("the part") { parts(inboxOf(folder)).size == 1 }
            cut.close() // in this session that is SIGTERM to the script, then the end of its input: the trap's path
            waitFor("the part to go") { parts(inboxOf(folder)).isEmpty() }
        }
        assertFalse(File(inboxOf(folder), "cut.bin").exists(), "nothing was published")
        assertEquals(0, put(folder, "cut.bin", body(100)).exit, "the name is still free")
    }

    @Test fun `a TERM to the script is held until its read ends, and then ends it through the cleanup`() {
        val folder = project()
        runBlocking {
            val ch = session().openStream(TransferScripts.putArgv(cap))
            try {
                ch.write(header(folder.path, "term.bin", 100).toByteArray() + body(40))
                waitFor("the part") { parts(inboxOf(folder)).size == 1 }
                val sh = ProcessHandle.current().children().filter { it.info().arguments().orElse(emptyArray()).contains("paddock-put") }.toList().single()
                // bash and dash hold a trapped signal until the foreground `head` returns, so the trap, not the signal's own death, ends the script.
                sh.destroy()
                Thread.sleep(300)
                assertTrue(sh.isAlive, "a trapped TERM waits for the read: without the trap the shell would die at once and leave the cleanup to its luck")
                assertEquals(1, parts(inboxOf(folder)).size, "the part is still being written")
                ch.closeStdin()
                assertEquals(143, ch.awaitExit(), "the trap's own status")
                assertEquals(emptyList(), parts(inboxOf(folder)))
            } finally { ch.close() }
        }
        assertFalse(File(inboxOf(folder), "term.bin").exists(), "nothing was published")
    }

    @Test fun `the sweep removes a part older than an hour and leaves a younger one, a published file and a hidden file that is not a part`() {
        val folder = project()
        assertEquals(0, put(folder, "first.bin", body(5)).exit)
        val inbox = inboxOf(folder)
        fun plant(name: String, minutesOld: Long): File = File(inbox, name).also {
            it.writeText("x"); Files.setLastModifiedTime(it.toPath(), FileTime.fromMillis(System.currentTimeMillis() - minutesOld * 60_000))
        }
        val old = plant(".old.bin.part.AbCd1234", 120)
        val young = plant(".young.bin.part.AbCd1234", 10)
        val oldPublished = plant("kept-old.bin", 600)
        val oldHidden = plant(".keep", 600)
        val oldDir = File(inbox, ".dir.part.x").also { it.mkdirs(); Files.setLastModifiedTime(it.toPath(), FileTime.fromMillis(System.currentTimeMillis() - 7_200_000)) }
        val elsewhere = File(project("outside"), "target").also { it.writeText("t") }
        val link = File(inbox, ".link.part.x").also { Files.createSymbolicLink(it.toPath(), elsewhere.toPath()) }

        assertEquals(0, put(folder, "second.bin", body(5)).exit)
        assertFalse(old.exists(), "a part over an hour old goes")
        assertTrue(young.exists() && oldPublished.exists() && oldHidden.exists(), "a younger part, a published file and a hidden file stay")
        assertTrue(oldDir.isDirectory && Files.isSymbolicLink(link.toPath()) && elsewhere.exists(), "only regular files are swept, and no link is followed")
    }

    // ---- the self-ignoring inbox, with real git ----------------------------------------------------------------------------

    private val gitEnv = mapOf(
        "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1", "GIT_AUTHOR_NAME" to "t", "GIT_AUTHOR_EMAIL" to "t@example.test",
        "GIT_COMMITTER_NAME" to "t", "GIT_COMMITTER_EMAIL" to "t@example.test", "GIT_TERMINAL_PROMPT" to "0",
    )

    private fun git(dir: File, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "init.defaultBranch=main", "-c", "protocol.file.allow=always", "-c", "commit.gpgsign=false") + args).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(gitEnv); pb.environment()["HOME"] = home.path
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "git ${args.first()} failed" }
        return out
    }

    private fun gitAvailable() = runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    /** Every file under a git directory, by path and content: what "nothing under .git changes" is measured by. */
    private fun treeHash(dir: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.walk(dir.toPath()).use { s -> s.sorted().forEach { p ->
            md.update(dir.toPath().relativize(p).toString().toByteArray()); md.update(0)
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) md.update(Files.readAllBytes(p))
        } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun repo(name: String): File = File(root, name).also { it.mkdirs(); git(it, "init"); File(it, "tracked.txt").writeText("t"); git(it, "add", "."); git(it, "commit", "-m", "x") }

    /** After a put into [folder] (inside the work tree of [top]): the work tree is clean, the inbox is ignored by `.paddock/.gitignore`, and [gitDirs] are as they were. */
    private fun assertSelfIgnoring(top: File, folder: File, vararg gitDirs: File) {
        git(top, "status", "--porcelain") // settle the index before measuring
        val before = gitDirs.map { treeHash(it) }
        val r = put(folder, "shot.png", body(2_000))
        assertEquals(0, r.exit)
        assertEquals(before, gitDirs.map { treeHash(it) }, "nothing under .git changes, and no git process is run")
        assertEquals("", git(top, "status", "--porcelain"), "git sees nothing new")
        val rel = top.toPath().relativize(File(folder, ".paddock").toPath()).toString()
        val said = git(top, "check-ignore", "-v", "$rel/inbox/shot.png")
        assertTrue(said.startsWith("$rel/.gitignore:1:*"), "check-ignore names the file the script wrote")
        assertEquals("*\n", File(folder, ".paddock/.gitignore").readText())
    }

    @Test fun `the inbox is ignored with git's own rules in a checkout's root`() {
        assumeTrue(gitAvailable())
        val top = repo("root")
        assertSelfIgnoring(top, top, File(top, ".git"))
    }

    @Test fun `the inbox is ignored in a subfolder of a checkout`() {
        assumeTrue(gitAvailable())
        val top = repo("subs")
        val sub = File(top, "pkg/app").also { it.mkdirs(); File(it, "f.txt").writeText("f") }
        git(top, "add", "."); git(top, "commit", "-m", "sub")
        assertSelfIgnoring(top, sub, File(top, ".git"))
    }

    @Test fun `the inbox is ignored in a linked worktree`() {
        assumeTrue(gitAvailable())
        val top = repo("main")
        val linked = File(root, "linked")
        git(top, "worktree", "add", "-b", "linked", linked.path)
        git(linked, "status", "--porcelain")
        assertSelfIgnoring(linked, linked, File(top, ".git"))
    }

    @Test fun `the inbox is ignored in a submodule`() {
        assumeTrue(gitAvailable())
        val lib = repo("lib")
        val top = repo("super")
        git(top, "submodule", "add", lib.path, "libs/lib")
        git(top, "commit", "-m", "add the submodule")
        val sub = File(top, "libs/lib")
        git(sub, "status", "--porcelain")
        assertSelfIgnoring(sub, sub, File(top, ".git"))
        assertEquals("", git(top, "status", "--porcelain"), "the superproject sees nothing either")
    }

    // ---- SEC-4: the header, the names, the folders ---------------------------------------------------------------------------

    @Test fun `every header value outside its allowlist is refused before any file is touched`() {
        val folder = project("host dir")
        val before = tree(root)
        val sizes = listOf("+5" to 20, "-5" to 20, "5 " to 20, " 5" to 20, "0x10" to 20, "1e3" to 20, "" to 20, "0" to 20, "00" to 20, "5.0" to 20,
            "123456789012345678901" to 20, "9999999999999999" to 20, (cap + 1).toString() to 17, "99999999999999" to 17)
        for ((n, exit) in sizes) assertEquals(exit, putRaw("${folder.path}\nok.bin\n$n\n").exit, "size ${n.length} characters")
        val names = listOf("a/b", "../x", ".hidden", "-rf", "-", " lead", "trail ", "a b", "a;b", "a\u0001b", "a\tb", "é.png", "x".repeat(101), "")
        for (n in names) assertEquals(TransferRefusal.BadName.exit, putRaw("${folder.path}\n$n\n5\n").exit, "name of ${n.length} characters")
        // A name with a line break is the header's next line, so the size is then the wrong line.
        assertEquals(TransferRefusal.BadCount.exit, putRaw("${folder.path}\nna\nme\n5\n").exit)
        for (f in listOf("", "proj", "./proj", "~", "~/x", "${folder.path}\u0009", "${folder.path}\u000d", "${folder.path}\u0001")) {
            assertEquals(TransferRefusal.BadPath.exit, putRaw("$f\nok.bin\n5\n").exit, "folder of ${f.length} characters")
        }
        // A header that ends early.
        assertEquals(TransferRefusal.BadPath.exit, run(TransferScripts.putArgv(cap), ByteArray(0)).exit)
        assertEquals(TransferRefusal.BadName.exit, run(TransferScripts.putArgv(cap), "${folder.path}\n".toByteArray()).exit)
        assertEquals(TransferRefusal.BadCount.exit, run(TransferScripts.putArgv(cap), "${folder.path}\nok.bin\n".toByteArray()).exit)
        // And a cap that is not a number.
        for (c in listOf("", "x", "-1", "1 2", "1".repeat(16))) {
            val argv = listOf("/bin/sh", "-c", TransferScripts.PUT, "paddock-put") + listOfNotNull(c.takeIf { it.isNotEmpty() })
            assertEquals(TransferRefusal.BadCount.exit, run(argv, header(folder.path, "ok.bin", 5).toByteArray() + body(5)).exit, "cap of ${c.length} characters")
        }
        assertEquals(before, tree(root), "the folder, and everything near it, is as it was")
    }

    @Test fun `a file, a link, a directory, a link to a directory and a dangling link at the published name each get nothing`() {
        val folder = project()
        val inbox = inboxOf(folder).also { it.mkdirs() }
        val elsewhere = File(project("elsewhere"), "dir").also { it.mkdirs() }
        val targetFile = File(project("elsewhere2"), "victim.txt").also { it.writeText("untouched") }

        File(inbox, "is-file").writeText("old")
        File(inbox, "is-dir").mkdirs()
        File(inbox, "is-full-dir").also { it.mkdirs(); File(it, "keep").writeText("k") }
        Files.createSymbolicLink(File(inbox, "is-link").toPath(), targetFile.toPath())
        Files.createSymbolicLink(File(inbox, "is-dirlink").toPath(), elsewhere.toPath())
        Files.createSymbolicLink(File(inbox, "is-dangling").toPath(), File(root, "nowhere").toPath())

        for (name in listOf("is-file", "is-dir", "is-full-dir", "is-link", "is-dirlink", "is-dangling")) expectRefused(put(folder, name, body(20)), TransferRefusal.Exists)

        assertEquals("old", File(inbox, "is-file").readText())
        assertEquals(emptyList(), File(inbox, "is-dir").list().orEmpty().toList(), "nothing went inside the directory")
        assertEquals(listOf("keep"), File(inbox, "is-full-dir").list().orEmpty().toList())
        assertEquals(emptyList(), elsewhere.list().orEmpty().toList(), "nothing went through the link to a directory")
        assertEquals("untouched", targetFile.readText(), "nothing was written through the link to a file")
        assertFalse(File(root, "nowhere").exists(), "nor through the dangling one")
        assertTrue(Files.isSymbolicLink(File(inbox, "is-dirlink").toPath()) && Files.isSymbolicLink(File(inbox, "is-dangling").toPath()))
        assertEquals(emptyList(), parts(inbox))
    }

    @Test fun `ln would have put the file inside a directory at the name, which is why the script uses link`() {
        val dir = project("lnproof")
        val parts = listOf("p1", "p2").map { n -> File(dir, n).also { it.writeText("file") } }
        val taken = File(dir, "taken").also { it.mkdirs() }
        val viaLink = File(dir, "via-link").also { Files.createSymbolicLink(it.toPath(), taken.toPath()) }
        for ((part, target) in parts.zip(listOf(taken, viaLink))) {
            val p = ProcessBuilder(realTool("ln"), "--", part.path, target.path).redirectErrorStream(true).start()
            p.inputStream.readBytes(); assertEquals(0, p.waitFor(), "ln succeeds, into the directory")
        }
        assertEquals(listOf("p1", "p2"), taken.list().orEmpty().sorted(), "both files went inside it, through the link too: a put that used ln would have written there")
        // link, which is link(2), refuses both.
        for (target in listOf(taken, viaLink)) {
            val p = ProcessBuilder(realTool("link"), parts[0].path, target.path).redirectErrorStream(true).start()
            p.inputStream.readBytes(); assertNotEquals(0, p.waitFor())
        }
        assertEquals(listOf("p1", "p2"), taken.list().orEmpty().sorted(), "link wrote nothing")
        assertFalse(TransferScripts.PUT.contains(Regex("\\bln\\b")) || TransferScripts.DROP.contains(Regex("\\bln\\b")), "and no script calls ln")
    }

    @Test fun `a link planted as the paddock folder or the inbox changes nothing outside the folder`() {
        val outside = File(root, "outside").also { it.mkdirs() }
        val before = tree(outside)
        val folder = project()
        Files.createSymbolicLink(File(folder, ".paddock").toPath(), outside.toPath())
        expectRefused(put(folder, "a.bin", body(5)), TransferRefusal.Symlink)
        assertEquals(before, tree(outside), "no gitignore, no inbox and no part went through it")

        val dangling = project("dangling")
        Files.createSymbolicLink(File(dangling, ".paddock").toPath(), File(root, "not-there").toPath())
        expectRefused(put(dangling, "a.bin", body(5)), TransferRefusal.Symlink)
        assertFalse(File(root, "not-there").exists(), "a dangling link is not made good by mkdir")

        val inLink = project("inbox-link")
        File(inLink, ".paddock").mkdirs()
        Files.createSymbolicLink(File(inLink, ".paddock/inbox").toPath(), outside.toPath())
        expectRefused(put(inLink, "a.bin", body(5)), TransferRefusal.Symlink)
        assertEquals(before, tree(outside))

        val notDirs = project("not-dirs")
        File(notDirs, ".paddock").writeText("a file")
        expectRefused(put(notDirs, "a.bin", body(5)), TransferRefusal.NotDirectory)
        val inboxFile = project("inbox-file")
        File(inboxFile, ".paddock").mkdirs(); File(inboxFile, ".paddock/inbox").writeText("a file")
        expectRefused(put(inboxFile, "a.bin", body(5)), TransferRefusal.NotDirectory)
    }

    @Test fun `a program the script needs that is missing is its own refusal and touches nothing`() {
        val folder = project()
        val writeTools = listOf("head", "wc", "mktemp", "link", "mkdir", "rmdir", "rm", "find")
        val bins = writeTools.associateWith { missing ->
            File(root, "bin-without-$missing").also { bin ->
                bin.mkdirs()
                for (t in writeTools.filter { it != missing }) Files.createSymbolicLink(File(bin, t).toPath(), File(realTool(t)).toPath())
            }
        }
        val before = tree(root)
        for ((missing, bin) in bins) assertEquals(TransferRefusal.MissingTool.exit, put(folder, "a.bin", body(5), s = session(path = bin.path)).exit, "without $missing")
        for ((missing, bin) in bins) assertEquals(TransferRefusal.MissingTool.exit, drop("~/Paddock", "a.bin", body(5), s = session(path = bin.path)).exit, "a drop without $missing")
        assertEquals(before, tree(root), "nothing was touched")
    }

    @Test fun `the script uses the shell's byte-wise rules whatever locale the account has`() {
        val folder = project()
        val utf8 = mapOf("LC_ALL" to "en_US.UTF-8", "LANG" to "en_US.UTF-8")
        assertEquals(TransferRefusal.BadName.exit, putRaw("${folder.path}\néa.png\n5\n", s = session(extra = utf8)).exit)
        assertEquals(0, put(folder, "plain.bin", body(5), s = session(extra = utf8)).exit)
        // A folder with non-ASCII letters is fine: bytes pass through.
        val accented = project("café 中")
        assertEquals(0, put(accented, "plain.bin", body(5), s = session(extra = utf8)).exit)
    }

    // ---- the drop folder (X10) ----------------------------------------------------------------------------------------------

    @Test fun `a drop creates its folder 0700 and writes a private file that is not executable`() {
        val r = drop("~/Paddock", "note.txt", body(300))
        assertEquals(0, r.exit); assertEquals(0, r.stdout.size)
        val folder = File(home, "Paddock")
        assertEquals("rwx------", mode(folder))
        assertEquals("rw-------", mode(File(folder, "note.txt")))
        assertFalse(File(folder, "note.txt").canExecute())
        assertContentEquals(body(300), File(folder, "note.txt").readBytes())
        assertFalse(File(folder, ".paddock").exists() || File(folder, ".gitignore").exists(), "a drop is not an inbox")
        // Trailing slashes, an absolute spelling and an existing folder (whose mode is not changed) are the same place.
        Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        assertEquals(0, drop("~/Paddock/", "b.txt", body(5)).exit)
        assertEquals(0, drop("${home.path}/Paddock//", "c.txt", body(5)).exit)
        assertEquals("rwxr-xr-x", mode(folder), "an existing folder keeps its mode")
        assertEquals(listOf("b.txt", "c.txt", "note.txt"), folder.list().orEmpty().sorted())
        // Never over a name that is there.
        assertEquals(TransferRefusal.Exists.exit, drop("~/Paddock", "note.txt", body(5)).exit)
        assertContentEquals(body(300), File(folder, "note.txt").readBytes())
    }

    @Test fun `a drop refuses home itself, a hidden folder, a link, a place outside home and a parent that is not there`() {
        File(home, ".ssh").mkdirs(); File(home, ".hidden").mkdirs(); File(home, "real").mkdirs(); File(home, "plain-file").writeText("x")
        Files.createSymbolicLink(File(home, "Paddock").toPath(), File(home, "real").toPath())
        Files.createSymbolicLink(File(home, "to-ssh").toPath(), File(home, ".ssh").toPath())
        Files.createSymbolicLink(File(home, "out").toPath(), File(root, "outside").also { it.mkdirs() }.toPath())
        val before = tree(root)
        val cases = listOf(
            "~" to TransferRefusal.Home, "~/" to TransferRefusal.Home, "~/." to TransferRefusal.Home, home.path to TransferRefusal.Home, "${home.path}/" to TransferRefusal.Home,
            "~/.ssh" to TransferRefusal.Hidden, "~/.hidden/x" to TransferRefusal.Hidden, "~/real/../.ssh" to TransferRefusal.Hidden, "~/to-ssh/x" to TransferRefusal.Hidden,
            "~/Paddock" to TransferRefusal.Symlink, "~/out" to TransferRefusal.Symlink, "~/to-ssh" to TransferRefusal.Symlink,
            "/usr/lib" to TransferRefusal.OutsideHome, "/tmp" to TransferRefusal.OutsideHome, "~/.." to TransferRefusal.OutsideHome, "/" to TransferRefusal.OutsideHome,
            "/usr/lib/not-yet" to TransferRefusal.OutsideHome, "~/out/x" to TransferRefusal.OutsideHome,
            "~/missing/child" to TransferRefusal.NotDirectory, "~/plain-file" to TransferRefusal.NotDirectory, "~/plain-file/x" to TransferRefusal.NotDirectory,
            "~nobody/x" to TransferRefusal.BadPath, "relative/x" to TransferRefusal.BadPath,
        )
        for ((value, why) in cases) assertEquals(why.exit, drop(value, "f.txt", body(5)).exit, "refusing a drop folder (${why.name})")
        assertEquals(before, tree(root), "no file, no folder, no part")
        // The same value, resolved, says the same, and the same reason.
        for ((value, why) in cases) assertEquals(why.exit, run(TransferScripts.resolveDropArgv(), "$value\n".toByteArray()).exit, "resolving a drop folder (${why.name})")
    }

    @Test fun `a drop folder owned by somebody else is refused`() {
        notRoot()
        val shared = session(extra = mapOf("HOME" to "/usr"))
        val before = tree(home)
        assertEquals(TransferRefusal.NotYours.exit, run(TransferScripts.dropArgv(cap), header("~/lib", "f.txt", 5).toByteArray() + body(5), shared).exit)
        assertEquals(TransferRefusal.NotYours.exit, run(TransferScripts.resolveDropArgv(), "~/lib\n".toByteArray(), shared).exit)
        assertEquals(before, tree(home))
    }

    @Test fun `a drop folder whose real path has a control character that only a link in the middle shows is refused, and so is a home that is not absolute`() {
        val odd = File(home, "w\td").also { it.mkdirs() }
        File(odd, "sub").mkdirs()
        Files.createSymbolicLink(File(home, "ln").toPath(), odd.toPath())
        val before = tree(root)
        for (value in listOf("~/ln/sub", "~/ln/new", "${home.path}/ln/sub")) {
            assertEquals(TransferRefusal.BadPath.exit, drop(value, "f.txt", body(5)).exit, "dropping through a link to a folder with a control character in its name")
            assertEquals(TransferRefusal.BadPath.exit, run(TransferScripts.resolveDropArgv(), "$value\n".toByteArray()).exit, "resolving it")
        }
        for (h in listOf("relative/home", "")) {
            val s = session(extra = mapOf("HOME" to h))
            assertEquals(TransferRefusal.OutsideHome.exit, drop("~/x", "f.txt", body(5), s).exit, "a HOME that is not an absolute path is no home")
            assertEquals(TransferRefusal.OutsideHome.exit, run(TransferScripts.resolveDropArgv(), "~\n".toByteArray(), s).exit)
        }
        assertEquals(before, tree(root), "nothing was made")
    }

    @Test fun `resolving a drop folder prints its real path, a new folder by its real parent, and creates nothing`() {
        val real = File(home, "real").also { it.mkdirs() }
        Files.createSymbolicLink(File(home, "alias").toPath(), real.toPath())
        val before = tree(root)
        fun resolve(v: String) = run(TransferScripts.resolveDropArgv(), "$v\n".toByteArray())
        assertEquals("${real.path}\n", resolve("~/real").stdout.toString(Charsets.UTF_8))
        assertEquals("${real.path}\n", resolve("~/alias/.").stdout.toString(Charsets.UTF_8), "a link in the middle is followed, the real path is what is shown")
        assertEquals("${File(home, "Paddock").path}\n", resolve("~/Paddock").stdout.toString(Charsets.UTF_8))
        assertEquals("${File(real, "inner").path}\n", resolve("~/alias/inner").stdout.toString(Charsets.UTF_8))
        assertEquals(before, tree(root))
    }

    // ---- stat, get, list, check ---------------------------------------------------------------------------------------------

    private fun stat(folder: File, path: String, c: Long = cap, s: LocalProcessSession = session()) =
        run(TransferScripts.statArgv(c), "${folder.path}\n$path\n".toByteArray(), s)

    private fun get(folder: File, path: String, want: Long, c: Long = cap, s: LocalProcessSession = session()) =
        run(TransferScripts.getArgv(c), "${folder.path}\n$path\n$want\n".toByteArray(), s)

    private fun statOf(r: Ran) = r.stdout.toString(Charsets.UTF_8).trimEnd('\n').split('\t')

    private fun file(dir: File, name: String, content: ByteArray, mtimeSeconds: Long? = null) = File(dir, name).also {
        it.parentFile.mkdirs(); it.writeBytes(content); if (mtimeSeconds != null) Files.setLastModifiedTime(it.toPath(), FileTime.fromMillis(mtimeSeconds * 1000))
    }

    @Test fun `stat gives the size, the time and the real path of a regular file under the folder`() {
        val folder = project("agent dir")
        val f = file(folder, "out/report one.txt", body(1234), mtimeSeconds = 1_700_000_000)
        Files.createSymbolicLink(File(folder, "alias").toPath(), File(folder, "out").toPath())
        for (path in listOf(f.path, "${folder.path}/alias/report one.txt", "${folder.path}/out/../out/report one.txt")) {
            val r = stat(folder, path)
            assertEquals(0, r.exit, "a path through a link in the middle is judged on its real path")
            assertEquals(listOf("1234", "1700000000", f.path), statOf(r))
        }
    }

    @Test fun `stat refuses a link out of the folder, a link in it, a directory, a missing file, a file over the cap and a path outside`() {
        val folder = project("agent")
        val inside = file(folder, "a.txt", body(10))
        val secret = file(project("secret"), "id", body(10))
        Files.createSymbolicLink(File(folder, "to-secret").toPath(), secret.toPath())
        Files.createSymbolicLink(File(folder, "to-inside").toPath(), inside.toPath())
        Files.createSymbolicLink(File(folder, "dir-out").toPath(), secret.parentFile.toPath())
        File(folder, "sub").mkdirs()
        val sibling = file(File(root, "agent-other"), "x.txt", body(10))
        val big = file(folder, "big.bin", body(2_000))
        val fifo = fifo(File(folder, "pipe"))
        val dangling = File(folder, "dangling").also { Files.createSymbolicLink(it.toPath(), File(root, "gone").toPath()) }
        val cases = listOf(
            File(folder, "to-secret").path to TransferRefusal.Symlink, File(folder, "to-inside").path to TransferRefusal.Symlink, dangling.path to TransferRefusal.Symlink,
            "${folder.path}/dir-out/id" to TransferRefusal.OutsideFolder, "${folder.path}/../secret/id" to TransferRefusal.OutsideFolder,
            sibling.path to TransferRefusal.OutsideFolder, "/etc/hostname" to TransferRefusal.OutsideFolder, folder.path to TransferRefusal.OutsideFolder,
            File(folder, "sub").path to TransferRefusal.NotRegular, "${folder.path}/sub/" to TransferRefusal.NotRegular, "${folder.path}/sub/.." to TransferRefusal.NotRegular, fifo.path to TransferRefusal.NotRegular,
            File(folder, "nope.txt").path to TransferRefusal.NotFound, "${folder.path}/nope/x" to TransferRefusal.NotFound,
            "relative.txt" to TransferRefusal.BadPath, "" to TransferRefusal.BadPath, "${folder.path}/a\u0001.txt" to TransferRefusal.BadPath, "${folder.path}/a\tb.txt" to TransferRefusal.BadPath,
        )
        for ((path, why) in cases) assertEquals(why.exit, stat(folder, path).exit, "stat (${why.name})")
        assertEquals(TransferRefusal.OverCap.exit, stat(folder, big.path, c = 1_999).exit)
        assertEquals(0, stat(folder, big.path, c = 2_000).exit, "a file at the cap is fine")
        assertEquals(TransferRefusal.NotDirectory.exit, run(TransferScripts.statArgv(cap), "${folder.path}/nope\n${inside.path}\n".toByteArray()).exit)
        assertEquals(TransferRefusal.BadPath.exit, run(TransferScripts.statArgv(cap), "rel\n${inside.path}\n".toByteArray()).exit)
        assertEquals(TransferRefusal.BadCount.exit, run(listOf("/bin/sh", "-c", TransferScripts.STAT, "paddock-stat"), "${folder.path}\n${inside.path}\n".toByteArray()).exit)
        // A link in the middle to a folder with a control character in its name: the path given has none, the real one does, and it would split the answer's columns.
        val odd = File(folder, "a\tb").also { it.mkdirs() }
        file(odd, "f.txt", body(10))
        Files.createSymbolicLink(File(folder, "ln-odd").toPath(), odd.toPath())
        assertEquals(TransferRefusal.BadPath.exit, stat(folder, "${folder.path}/ln-odd/f.txt").exit)
        assertEquals(TransferRefusal.BadPath.exit, get(folder, "${folder.path}/ln-odd/f.txt", 10).exit)
    }

    @Test fun `get streams exactly the statted size, byte for byte, and nothing else`() {
        val folder = project()
        val content = "\u0000\n\r\n".toByteArray() + body(70_000)
        val f = file(folder, "bin.dat", content)
        val r = get(folder, f.path, content.size.toLong())
        assertEquals(0, r.exit)
        assertContentEquals(content, r.stdout)
        assertEquals(sha256(content), sha256(r.stdout))
        // An empty file is a size of nothing, and it is not an error.
        val empty = file(folder, "empty", ByteArray(0))
        assertEquals(0, get(folder, empty.path, 0).exit)
    }

    @Test fun `get judges the path as stat does and refuses a file whose size is no longer the one looked at`() {
        val folder = project()
        val f = file(folder, "a.txt", body(100))
        val secret = file(project("secret"), "id", body(10))
        Files.createSymbolicLink(File(folder, "link").toPath(), secret.toPath())
        assertEquals(TransferRefusal.Changed.exit, get(folder, f.path, 99).exit, "it grew")
        assertEquals(TransferRefusal.Changed.exit, get(folder, f.path, 101).exit, "it shrank")
        assertEquals(TransferRefusal.Symlink.exit, get(folder, File(folder, "link").path, 10).exit)
        assertEquals(TransferRefusal.OutsideFolder.exit, get(folder, secret.path, 10).exit)
        assertEquals(TransferRefusal.NotRegular.exit, get(folder, folder.path + "/", 10).exit)
        assertEquals(TransferRefusal.OverCap.exit, get(folder, f.path, 100, c = 99).exit)
        for (bad in listOf("", "x", "-1", "1 2", "1".repeat(16))) {
            assertEquals(TransferRefusal.BadCount.exit, run(TransferScripts.getArgv(cap), "${folder.path}\n${f.path}\n$bad\n".toByteArray()).exit, "a size of ${bad.length} characters")
        }
        for (r in listOf(get(folder, f.path, 99), get(folder, File(folder, "link").path, 10))) assertEquals(0, r.stdout.size, "a refusal sends no byte")
    }

    private fun lines(r: Ran) = r.stdout.toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }

    @Test fun `list is bounded to 50, newest first, with name, size and time, and skips what is not a plain visible file with a clean name`() {
        val dir = File(project("agent"), ".paddock/outbox").also { it.mkdirs() }
        val base = 1_700_000_000L
        for (i in 0 until 60) file(dir, "f%02d.txt".format(i), body(i + 1), base + i * 10L)
        val r = run(TransferScripts.listArgv(), "${dir.path}\n".toByteArray())
        assertEquals(0, r.exit)
        val rows = lines(r).map { it.split('\t') }
        assertEquals(50, rows.size)
        assertEquals(List(50) { "f%02d.txt".format(59 - it) }, rows.map { it[0] }, "newest first")
        assertEquals(listOf("60", (base + 590).toString()), rows.first().drop(1))
        assertTrue(rows.all { it.size == 3 })
    }

    @Test fun `list skips names with a control character including tab and line break, hidden files, links, directories and other kinds, and keeps odd but clean names`() {
        val dir = File(project("agent"), ".paddock/outbox").also { it.mkdirs() }
        val base = 1_700_000_000L
        val kept = listOf("plain.txt", "with space.txt", "-dash.txt", "%s%d.txt", "star*.txt", "café 中.txt", "back\\slash.txt", "quote\"s.txt", "dollar\$HOME.txt")
        kept.forEachIndexed { i, n -> file(dir, n, body(3), base + i) }
        for (bad in listOf("tab\there", "new\nline", "bell\u0007", "ctl\u0001", "esc\u001b[1m", "cr\rx", "del\u007f")) file(dir, bad, body(3), base + 500)
        file(dir, ".hidden", body(3), base + 600)
        File(dir, "subdir").mkdirs()
        Files.createSymbolicLink(File(dir, "link-to-file").toPath(), File(dir, "plain.txt").toPath())
        Files.createSymbolicLink(File(dir, "link-to-dir").toPath(), File(dir, "subdir").toPath())
        fifo(File(dir, "pipe"))
        val rows = lines(run(TransferScripts.listArgv(), "${dir.path}\n".toByteArray())).map { it.split('\t') }
        assertEquals(kept.sorted(), rows.map { it[0] }.sorted())
        assertTrue(rows.all { it.size == 3 && it[1] == "3" })
    }

    @Test fun `list looks at no more than 1000 names, and a folder that is missing, a file or a link is refused`() {
        val many = File(project("agent"), "many").also { it.mkdirs() }
        for (i in 1..1_100) File(many, "a%04d".format(i)).writeText("x")
        Files.setLastModifiedTime(File(many, "a1100").toPath(), FileTime.fromMillis(2_000_000_000_000))
        val names = lines(run(TransferScripts.listArgv(), "${many.path}\n".toByteArray())).map { it.substringBefore('\t') }
        assertEquals(50, names.size)
        assertFalse("a1100" in names, "the 1,001st name was not looked at, though it is the newest")

        val agent = project("agent2")
        val file = file(agent, "f", body(1))
        Files.createSymbolicLink(File(agent, "ln").toPath(), many.toPath())
        fun list(p: String) = run(TransferScripts.listArgv(), "$p\n".toByteArray()).exit
        assertEquals(TransferRefusal.NotFound.exit, list("${agent.path}/.paddock/outbox"), "no outbox yet is not an error the user needs to read twice: it is an empty list")
        assertEquals(TransferRefusal.NotDirectory.exit, list(file.path))
        assertEquals(TransferRefusal.Symlink.exit, list("${agent.path}/ln"))
        assertEquals(TransferRefusal.BadPath.exit, list("relative"))
        assertEquals(TransferRefusal.BadPath.exit, list("/tmp/a\u0001b"))
        notRoot()
        assertEquals(TransferRefusal.NotYours.exit, list("/usr/lib"))
    }

    @Test fun `check says present with the size or absent, and creates nothing`() {
        val folder = project()
        val before = tree(root)
        fun check(f: File, name: String) = run(TransferScripts.checkArgv(), "${f.path}\n$name\n".toByteArray())
        assertEquals("absent\n", check(folder, "a.png").stdout.toString(Charsets.UTF_8))
        assertEquals(before, tree(root), "asking creates no folder")
        assertEquals(0, put(folder, "a.png", body(321)).exit)
        val present = check(folder, "a.png")
        assertEquals(0, present.exit)
        assertEquals("present\t321\n", present.stdout.toString(Charsets.UTF_8))
        assertEquals("absent\n", check(folder, "b.png").stdout.toString(Charsets.UTF_8))
        // Something at the name that is not a file the script made is not "present".
        val inbox = inboxOf(folder)
        Files.createSymbolicLink(File(inbox, "ln.png").toPath(), File(inbox, "a.png").toPath())
        File(inbox, "dir.png").mkdirs()
        assertEquals(TransferRefusal.Symlink.exit, check(folder, "ln.png").exit)
        assertEquals(TransferRefusal.NotRegular.exit, check(folder, "dir.png").exit)
        assertEquals(TransferRefusal.BadName.exit, check(folder, "../a.png").exit)
        assertEquals(TransferRefusal.BadName.exit, check(folder, "x".repeat(101)).exit)
        assertEquals("absent\n", check(folder, "x".repeat(100)).stdout.toString(Charsets.UTF_8), "a hundred is the longest")
        assertEquals(TransferRefusal.BadPath.exit, run(TransferScripts.checkArgv(), "${folder.path}\t\na.png\n".toByteArray()).exit)
        assertEquals(TransferRefusal.BadPath.exit, run(TransferScripts.checkArgv(), "rel\na.png\n".toByteArray()).exit)
        assertEquals(TransferRefusal.NotDirectory.exit, check(File(root, "nope"), "a.png").exit)
        val linked = project("linked")
        Files.createSymbolicLink(File(linked, ".paddock").toPath(), File(folder, ".paddock").toPath())
        assertEquals(TransferRefusal.Symlink.exit, check(linked, "a.png").exit, "a link as .paddock is refused for a read too")
    }

    // ---- other tools: BSD stand-ins on PATH, as WakeMacosTest does -----------------------------------------------------------

    private fun standIns(overrides: Map<String, String> = emptyMap()): String {
        val bin = File(root, "bsd-bin").also { it.mkdirs() }
        for (t in listOf("head", "wc", "mktemp", "link", "mkdir", "rmdir", "rm", "find", "stat", "sort")) {
            val f = File(bin, t)
            if (t in overrides) { f.writeText("#!/bin/sh\n${overrides.getValue(t)}\n"); f.setExecutable(true) }
            else if (!f.exists()) Files.createSymbolicLink(f.toPath(), File(realTool(t)).toPath())
        }
        return bin.path
    }

    /** BSD's `stat` has no `-c` (it exits 1 for it) and `-f %m` is its mtime; its `wc` pads with spaces; its `mktemp` takes only trailing X's. */
    private fun bsd(extra: Map<String, String> = emptyMap()) = standIns(mapOf(
        "stat" to "[ \"\$1\" = -f ] && [ \"\$2\" = %m ] || { echo \"stat: illegal option -- c\" >&2; exit 1; }; shift 2; [ \"\$1\" = -- ] && shift; exec ${realTool("stat")} -c %Y -- \"\$1\"",
        "wc" to "n=\$(${realTool("wc")} \"\$@\"); printf \"%8s\\n\" \"\$n\"",
        "mktemp" to "[ \$# -eq 1 ] || exit 1; case \"\$1\" in -*) exit 1;; *XXXXXXXX) exec ${realTool("mktemp")} \"\$1\";; *) exit 1;; esac",
    ) + extra)

    @Test fun `the put, drop, stat, list and check scripts work with BSD's stat, space-padded wc, trailing-X mktemp and a link found on PATH`() {
        val s = session(path = bsd())
        val folder = project()
        val content = body(5_000)
        assertEquals(0, put(folder, "a.bin", content, s = s).exit)
        assertContentEquals(content, File(inboxOf(folder), "a.bin").readBytes())
        assertEquals(emptyList(), parts(inboxOf(folder)))
        assertEquals(0, drop("~/Paddock", "d.bin", content, s = s).exit)
        assertContentEquals(content, File(home, "Paddock/d.bin").readBytes())

        val f = file(folder, "out.txt", body(77), mtimeSeconds = 1_700_000_123)
        assertEquals(listOf("77", "1700000123", f.path), statOf(stat(folder, f.path, s = s)))
        assertContentEquals(body(77), get(folder, f.path, 77, s = s).stdout)
        val dir = File(folder, ".paddock/outbox")
        file(dir, "o1.txt", body(5), 1_700_000_001); file(dir, "o2.txt", body(6), 1_700_000_002)
        val listed = lines(run(TransferScripts.listArgv(), "${dir.path}\n".toByteArray(), s))
        assertEquals(listOf("o2.txt\t6\t1700000002", "o1.txt\t5\t1700000001"), listed)
        assertEquals("present\t5000\n", run(TransferScripts.checkArgv(), "${folder.path}\na.bin\n".toByteArray(), s).stdout.toString(Charsets.UTF_8))
        assertEquals(TransferRefusal.Exists.exit, put(folder, "a.bin", content, s = s).exit)
        assertEquals(TransferRefusal.Truncated.exit, put(folder, "short.bin", body(3), declared = 9, s = s).exit)
    }

    @Test fun `a read that the stat reported smaller than the file is bounded at one byte over, so a file that grew is seen and not streamed whole`() {
        val folder = project()
        val f = file(folder, "growing.log", body(10_000))
        // The size check believes 100, as if the file grew after it: wc says so. The read is still bounded by what the phone asked for, plus one.
        val s = session(path = standIns(mapOf("wc" to "echo 100")))
        val r = get(folder, f.path, 100, s = s)
        assertEquals(0, r.exit)
        assertEquals(101, r.stdout.size, "at most the looked-at size plus one byte reaches the phone, which counts and refuses")
    }

    // ---- the scripts themselves ---------------------------------------------------------------------------------------------

    private val scripts = mapOf("put" to TransferScripts.PUT, "drop" to TransferScripts.DROP, "resolve-drop" to TransferScripts.RESOLVE_DROP, "stat" to TransferScripts.STAT,
        "get" to TransferScripts.GET, "list" to TransferScripts.LIST, "check" to TransferScripts.CHECK)

    @Test fun `every script is one line that argvToCommand carries through every login shell`() {
        for ((name, s) in scripts) {
            assertTrue(s.none { it == '\'' || it == '\\' || it == '\n' || it == '\r' || it == '\u0000' }, "$name has a character no quoting form carries")
            assertEquals(s, argvToCommand(listOf(s)).removeSurrounding("'"), "$name is quoted as one word")
        }
        for (argv in listOf(TransferScripts.putArgv(cap), TransferScripts.dropArgv(cap), TransferScripts.resolveDropArgv(), TransferScripts.statArgv(cap),
            TransferScripts.getArgv(cap), TransferScripts.listArgv(), TransferScripts.checkArgv())) argvToCommand(argv)
        assertEquals(listOf("/bin/sh", "-c", TransferScripts.PUT, "paddock-put", "1000000"), TransferScripts.putArgv(cap))
        assertEquals(listOf("/bin/sh", "-c", TransferScripts.LIST, "paddock-list"), TransferScripts.listArgv())
    }

    private fun syntaxCheck(shell: List<String>, name: String, script: String) {
        val f = File(root, "syntax-$name.sh").also { it.writeText(script + "\n") }
        val p = ProcessBuilder(shell + listOf("-n", f.path)).redirectErrorStream(true).start()
        val said = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), "$name does not parse under ${shell.joinToString(" ")}: $said")
    }

    @Test fun `sh -n passes for every script under sh, bash in POSIX mode and, where installed, dash and shellcheck`() {
        for ((name, s) in scripts) {
            syntaxCheck(listOf("/bin/sh"), name, s)
            syntaxCheck(listOf("bash", "--posix"), name, s)
            if (File("/usr/bin/dash").canExecute() || File("/bin/dash").canExecute()) syntaxCheck(listOf("dash"), name, s)
        }
        val shellcheck = runCatching { ProcessBuilder("shellcheck", "--version").start().waitFor() == 0 }.getOrDefault(false)
        if (shellcheck) for ((name, s) in scripts) {
            val f = File(root, "check-$name.sh").also { it.writeText("#!/bin/sh\n$s\n") }
            val p = ProcessBuilder("shellcheck", "-s", "sh", "-S", "error", f.path).redirectErrorStream(true).start()
            val said = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, p.waitFor(), "shellcheck on $name: $said")
        }
    }

    @Test fun `every script sets the prelude first, checks the programs it calls, and calls no program it did not check`() {
        for ((name, s) in scripts) {
            assertTrue(s.startsWith("LC_ALL=C; export LC_ALL; umask 077; set -C;"), "$name sets LC_ALL=C, umask 077 and set -C before anything else")
        }
        val expected = mapOf(
            "put" to "head wc mktemp link mkdir rmdir rm find", "drop" to "head wc mktemp link mkdir rmdir rm find", "resolve-drop" to "", "stat" to "wc stat",
            "get" to "head wc", "list" to "wc stat sort head", "check" to "wc",
        )
        val forbidden = setOf("ln", "cat", "sed", "awk", "grep", "dirname", "basename", "readlink", "realpath", "id", "ls", "tr", "cut", "git", "eval", "env", "xargs", "sh", "bash")
        val known = setOf("head", "wc", "mktemp", "link", "mkdir", "rmdir", "rm", "find", "stat", "sort") + forbidden
        for ((name, s) in scripts) {
            val checked = Regex("for t in ([a-z ]+); do command -v").find(s)?.groupValues?.get(1)?.split(' ').orEmpty().toSet()
            assertEquals(expected.getValue(name).split(' ').filter { it.isNotEmpty() }.toSet(), checked, "$name checks the programs it needs")
            // The words of the script outside the checking loop and its quoted strings: any that names a program is a call.
            val words = s.replace(Regex("for t in [a-z ]+; do"), "").replace(Regex("\"[^\"]*\""), " ").split(Regex("[\\s;|&(){}]+"))
            val called = words.filter { it in known }.toSet()
            assertTrue(checked.containsAll(called), "$name calls a program it does not check")
            assertTrue(called.none { it in forbidden }, "$name calls a program it should not")
        }
    }

    @Test fun `each refusal has a distinct exit status the shell does not use, a sentence that says what was not done, and a place in a script`() {
        val all = TransferRefusal.entries
        assertEquals(all.size, all.map { it.exit }.toSet().size)
        assertTrue(all.all { it.exit in 10..99 }, "never 1, 2, 126, 127 or 128 and up")
        assertTrue(all.all { it.sentence.endsWith(".") && it.sentence.contains("Nothing was") })
        assertTrue(all.all { it.sentence.length <= 160 }, "a sentence fits a journal note")
        assertEquals(TransferRefusal.Exists, TransferRefusal.forExit(15))
        assertEquals(null, TransferRefusal.forExit(0))
        assertEquals(null, TransferRefusal.forExit(143))
        assertEquals("over_cap", TransferRefusal.OverCap.code)
        assertEquals("outside_home", TransferRefusal.OutsideHome.code)
        val everything = scripts.values.joinToString(" ")
        for (r in all) assertTrue(everything.contains("exit ${r.exit}"), "no script exits ${r.name}")
    }

}
