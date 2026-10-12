package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.files.OpenableTypes.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenableTypesTest {
    private fun kind(n: String) = OpenableTypes.kindOf(n)

    @Test fun imagesPdfAndPlainTextOpen() {
        assertEquals(Kind.Openable("image/png"), kind("shot.png"))
        assertEquals(Kind.Openable("image/jpeg"), kind("a.JPG"))
        assertEquals(Kind.Openable("image/jpeg"), kind("a.jpeg"))
        assertEquals(Kind.Openable("application/pdf"), kind("report.pdf"))
        assertEquals(Kind.Openable("text/plain"), kind("notes.md"))
        assertEquals(Kind.Openable("text/plain"), kind("data.json"))
        assertEquals(Kind.Openable("text/csv"), kind("table.csv"))
        assertEquals(Kind.Openable("text/plain"), kind("fix.patch"))
    }

    @Test fun anApkIsNeverOpened() {
        assertEquals(Kind.Apk, kind("app-release.apk"))
        assertEquals(Kind.Apk, kind("APP.APK"))
        assertEquals(OpenableTypes.APK, "application/vnd.android.package-archive")
    }

    @Test fun whatCarriesScriptOrRunsIsShareOnly() {
        for (n in listOf("a.svg", "page.html", "page.htm", "run.sh", "tool.exe", "x.jar", "a.js", "a.py", "a.xml", "m.mp4", "a.docx", "dump.bin", "no-extension", ".hidden")) {
            assertTrue("$n must not open", kind(n) is Kind.ShareOnly)
        }
    }

    @Test fun anExtensionHiddenBehindAnotherIsTheLastOne() {
        assertTrue("a script named like an image is judged by its true extension", kind("evil.png.sh") is Kind.ShareOnly)
        assertEquals(Kind.Openable("image/png"), kind("evil.sh.png"))
        assertTrue(kind("photo.png ") is Kind.ShareOnly)
    }

    @Test fun theExtensionRule() {
        assertEquals("", OpenableTypes.extensionOf("noext"))
        assertEquals("", OpenableTypes.extensionOf(".hidden"))
        assertEquals("", OpenableTypes.extensionOf("trailing."))
        assertEquals("gz", OpenableTypes.extensionOf("a.tar.GZ"))
    }

    @Test fun aShareOnlyTypeIsNeverAViewerRunnableType() {
        for (n in listOf("a.svg", "a.html", "a.js", "a.xml", "a.sh")) {
            val mime = (kind(n) as Kind.ShareOnly).mime
            assertEquals(OpenableTypes.OTHER, mime)
        }
    }
}
