package io.github.tuthan.paddock.files

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathTokensTest {
    private fun texts(line: String, cut: Boolean = false) = PathTokens.tokens(line, cut).map { it.text }

    /** The lines S3 saw agents print, and what a link may be made of. */
    @Test fun realLinesGiveTheirPaths() {
        val table = listOf(
            "⏺ Wrote app/build/outputs/apk/foss/release/app-foss-release.apk" to listOf("app/build/outputs/apk/foss/release/app-foss-release.apk"),
            "Created /tmp/report.pdf." to listOf("/tmp/report.pdf"),
            "(see docs/files.md)" to listOf("docs/files.md"),
            "  ⎿  Wrote 42 lines to src/main/Foo.kt" to listOf("src/main/Foo.kt"),
            "\$ ls -la ./out/log.txt" to listOf("./out/log.txt"),
            "Saved to build/x.zip, then docs/a.md; done" to listOf("build/x.zip", "docs/a.md"),
            "│ docs/files.md │" to listOf("docs/files.md"),
            "│docs/files.md│" to listOf("docs/files.md"),
            "**docs/files.md** and `src/a.kt`" to listOf("docs/files.md", "src/a.kt"),
            "\"docs/a.md\" 'docs/b.md'" to listOf("docs/a.md", "docs/b.md"),
            "<docs/a.md>" to listOf("docs/a.md"),
            "see README.md" to listOf("README.md"),
            "report.PDF" to listOf("report.PDF"),
            "run scripts/deploy now" to listOf("scripts/deploy"),
            "docs/日本語.md" to listOf("docs/日本語.md"),
        )
        for ((line, expected) in table) assertEquals(expected, texts(line), line)
    }

    @Test fun aTokenKnowsWhereItIsInTheLine() {
        val line = "⏺ Wrote app/build/x.apk (12 MB)."
        val token = PathTokens.tokens(line).single()
        assertEquals("app/build/x.apk", line.substring(token.start, token.end))
        assertEquals(token.text, line.substring(token.start, token.end))
        val quoted = "say \"a/b.txt\"."
        val inner = PathTokens.tokens(quoted).single()
        assertEquals("a/b.txt", quoted.substring(inner.start, inner.end))
    }

    @Test fun aPlaceInAFileIsCutOffTheToken() {
        assertEquals(listOf("src/main/Foo.kt"), texts("src/main/Foo.kt:42"))
        assertEquals(listOf("src/main/Foo.kt"), texts("src/main/Foo.kt:42:7:"))
        assertEquals(listOf("src/main/Foo.kt"), texts("error in (src/main/Foo.kt:42)."))
        assertEquals(emptyList(), texts("Foo.kt:abc"))
        assertEquals(emptyList(), texts("host:8080"))
    }

    @Test fun whatIsNotAPathIsNotTaken() {
        val table = listOf(
            "https://example.com/a/b.png",
            "see http://localhost:8080/x/y.json now",
            "--out=build/x.zip",
            "--out build",
            "3/4 done",
            "1/2",
            "2026/10/11",
            "10/11/2026 12:30",
            "Done.",
            "e.g. this, i.e. that",
            "version 1.2.3",
            "example.com and github.io",
            "-la",
            "-I/usr/include",
            "src/",
            "/",
            "//",
            "./",
            ".gitignore",
            "┌────────┐",
            "│ │",
            "⏺ ⎿ ● •",
            "user@host:path/file.txt",
            "a=b/c.txt",
            "\$HOME/x.txt",
            "C:\\Users\\me\\x.txt",
            "my\\ file.txt",
            "",
            "   ",
        )
        for (line in table) assertEquals(emptyList(), texts(line), line)
    }

    @Test fun aPathWithASpaceIsNotSupported() {
        assertEquals(emptyList(), texts("\"my file.txt\""))
        assertEquals(emptyList(), texts("cat \"dir/my file.txt\""))
        assertEquals(emptyList(), texts("'my file.txt'"))
        assertEquals(emptyList(), texts("--name=\"my file.txt\""))
        // Words outside the quotes are still read, and a quote with no space in it is nothing special.
        assertEquals(listOf("docs/a.md"), texts("\"my file.txt\" docs/a.md"))
        assertEquals(listOf("docs/a.md", "b.txt"), texts("\"docs/a.md\" \"b.txt\""))
        // An apostrophe in a word opens no quote.
        assertEquals(listOf("src/a.kt"), texts("don't touch src/a.kt, it's fine"))
    }

    @Test fun atMostEightTokensPerLine() {
        val line = (1..20).joinToString(" ") { "d/f$it.txt" }
        val tokens = texts(line)
        assertEquals(PathTokens.MAX_PER_LINE, tokens.size)
        assertEquals(8, tokens.size)
        assertEquals((1..8).map { "d/f$it.txt" }, tokens)
        assertEquals(8, texts(line, cut = true).size)
    }

    @Test fun neverTheLastTokenOfACutLine() {
        assertEquals(listOf("a/b.txt", "c/d.txt"), texts("a/b.txt c/d.txt"))
        assertEquals(listOf("a/b.txt"), texts("a/b.txt c/d.txt", cut = true))
        assertEquals(emptyList(), texts("Wrote src/main/Foo", cut = true))
        assertEquals(emptyList(), texts("Wrote src/main/Foo.kt", cut = true))
        assertEquals(listOf("src/main/Foo.kt"), texts("Wrote src/main/Foo.kt", cut = false))
        assertEquals(7, texts((1..7).joinToString(" ") { "d/f$it.txt" }, cut = false).size)
        assertEquals(6, texts((1..7).joinToString(" ") { "d/f$it.txt" }, cut = true).size)
    }

    @Test fun terminalDrawingAndHiddenCharactersAreNeverTaken() {
        assertEquals(emptyList(), texts("\u202Ea/b.txt\u200B"))
        assertEquals(emptyList(), texts("a/b\u0000.txt"))
        assertEquals(emptyList(), texts("a/\u2500b.txt"))
        assertEquals(emptyList(), texts("x".repeat(1_100) + "/a.txt"))
    }

    @Test fun anyLineIsBoundedAndNeverThrows() {
        val rnd = Random(20261011)
        val alphabet = "ab/./.. -=:\"'()[]*|\u2502\u23FA\u202E\u0000\t12.txt".toList()
        repeat(3_000) {
            val line = buildString { repeat(rnd.nextInt(0, 400)) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
            val tokens = PathTokens.tokens(line, rnd.nextBoolean())
            assertTrue(tokens.size <= 8)
            var previousEnd = 0
            for (t in tokens) {
                assertTrue(t.start >= previousEnd && t.end > t.start && t.end <= line.length && line.substring(t.start, t.end) == t.text)
                assertTrue(t.text.none { it.isWhitespace() || it.code < 0x20 })
                previousEnd = t.end
            }
        }
    }

    // ---- resolve ------------------------------------------------------------------------------------------------

    private val folder = "/home/dev/project"

    @Test fun aRelativeTokenResolvesAgainstTheAgentsFolder() {
        assertEquals("/home/dev/project/docs/files.md", PathTokens.resolve(folder, "docs/files.md"))
        assertEquals("/home/dev/project/out/log.txt", PathTokens.resolve(folder, "./out/log.txt"))
        assertEquals("/home/dev/project/b.txt", PathTokens.resolve(folder, "a/../b.txt"))
        assertEquals("/home/dev/project/docs/x.md", PathTokens.resolve("$folder/", "docs//./x.md"))
        assertEquals("/a.txt", PathTokens.resolve("/", "a.txt"))
        assertEquals("/home/dev/project/docs/files.md", PathTokens.resolve(folder, PathTokens.tokens("(see docs/files.md)").single()))
    }

    @Test fun anAbsoluteTokenInsideTheFolderResolvesToItself() {
        assertEquals("/home/dev/project/out/x.zip", PathTokens.resolve(folder, "/home/dev/project/out/x.zip"))
        assertEquals("/home/dev/project/x.zip", PathTokens.resolve(folder, "/home/dev/project/out/../x.zip"))
    }

    @Test fun aTokenThatLeavesTheFolderIsRefused() {
        for (token in listOf(
            "../x.txt", "a/../../x.txt", "../project/x.txt/../../y", "/tmp/report.pdf", "/home/dev/project", "/home/dev/project/",
            "/home/dev/projectile/x.txt", "/home/dev/project/../x.txt", "a/../..", ".", "./", "..", "", "/", "/../etc/passwd",
        )) {
            assertNull(PathTokens.resolve(folder, token), "token $token")
        }
    }

    @Test fun aFolderThatIsNotAbsoluteOrHoldsAControlCharacterRefusesEverything() {
        assertNull(PathTokens.resolve("relative/project", "a.txt"))
        assertNull(PathTokens.resolve("", "a.txt"))
        assertNull(PathTokens.resolve("/home/dev/pro\nject", "a.txt"))
        assertNull(PathTokens.resolve("/../project", "a.txt"))
        assertNull(PathTokens.resolve(folder, "a\u0000b.txt"))
        assertNull(PathTokens.resolve(folder, "a\nb.txt"))
        assertNull(PathTokens.resolve(folder, "a\u202Eb.txt"))
    }

    @Test fun tildeIsNotExpanded() {
        assertEquals("/home/dev/project/~/.ssh/config", PathTokens.resolve(folder, "~/.ssh/config"))
        assertEquals("/home/dev/project/~root/x", PathTokens.resolve(folder, "~root/x"))
    }
}
