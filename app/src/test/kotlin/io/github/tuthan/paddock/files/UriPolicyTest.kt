package io.github.tuthan.paddock.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UriPolicyTest {
    private val own = setOf("io.github.tuthan.paddock.files", "io.github.tuthan.paddock.androidx-startup")
    private val pkg = "io.github.tuthan.paddock"
    private val nobody: (String) -> String? = { null }

    private fun check(scheme: String?, authority: String?, resolve: (String) -> String? = nobody) =
        UriPolicy.check(scheme, authority, own, pkg, resolve)

    @Test fun anotherAppsContentUriIsTaken() = assertNull(check("content", "com.android.providers.media.documents") { "com.android.providers.media" })

    @Test fun aNullResolutionIsTakenAsAnotherAppsGrant() = assertNull("no installed provider: only the sender's grant can open it", check("content", "some.sender.fileprovider"))

    @Test fun everySchemeButContentIsRefused() {
        for (scheme in listOf("file", "http", "https", "android.resource", "ftp", "paddock", "", null, "CONTENT", "Content", "content ")) {
            assertEquals("scheme $scheme", UriRefusal.NotContent, check(scheme, "com.android.providers.media.documents"))
        }
    }

    @Test fun aFileUriToThePrivateFilesIsRefusedWithNothingRead() {
        // A file:// URI has no authority at all; it must still be refused on the scheme, whatever the path.
        assertEquals(UriRefusal.NotContent, check("file", null))
        assertEquals(UriRefusal.NotContent, check("file", ""))
    }

    @Test fun aMissingAuthorityIsRefused() {
        assertEquals(UriRefusal.NotContent, check("content", null))
        assertEquals(UriRefusal.NotContent, check("content", ""))
        assertEquals(UriRefusal.NotContent, check("content", "10@"))
    }

    @Test fun paddocksOwnAuthoritiesAreRefused() {
        for (a in own) assertEquals(a, UriRefusal.OwnProvider, check("content", a))
        assertEquals("authorities compare without case", UriRefusal.OwnProvider, check("content", "IO.GITHUB.TUTHAN.PADDOCK.FILES"))
    }

    @Test fun aUserIdOrUserInfoPrefixDoesNotHideAnOwnAuthority() {
        assertEquals(UriRefusal.OwnProvider, check("content", "0@io.github.tuthan.paddock.files"))
        assertEquals(UriRefusal.OwnProvider, check("content", "10@io.github.tuthan.paddock.files"))
        assertEquals(UriRefusal.OwnProvider, check("content", "a@b@io.github.tuthan.paddock.files"))
    }

    @Test fun anAuthorityThePlatformResolvesToPaddocksPackageIsRefused() {
        assertEquals(UriRefusal.OwnProvider, check("content", "some.alias.authority") { pkg })
    }

    @Test fun theResolverIsAskedAboutWhatItWillRouteOn() {
        val asked = mutableListOf<String>()
        check("content", "10@com.example.files") { asked += it; "com.example" }
        assertEquals(listOf("com.example.files"), asked)
    }

    @Test fun aResolverThatThrowsIsAnUnverifiedRefusalNotACrash() {
        assertEquals(UriRefusal.Unverified, check("content", "com.example.files") { throw IllegalStateException("package manager died") })
        assertEquals(UriRefusal.Unverified, check("content", "com.example.files") { throw SecurityException("no") })
    }

    @Test fun aNeighbouringPackageWithAPaddockLikeNameIsTaken() {
        assertNull(check("content", "io.github.tuthan.paddock.other") { "io.github.tuthan.paddockx" })
    }

    @Test fun everyRefusalSaysTheSameThing() {
        assertEquals(1, UriRefusal.entries.map { it.sentence }.toSet().size)
    }
}
