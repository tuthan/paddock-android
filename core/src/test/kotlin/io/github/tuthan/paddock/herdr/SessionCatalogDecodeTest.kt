package io.github.tuthan.paddock.herdr

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionCatalogDecodeTest {
    private val root = File(requireNotNull(System.getProperty("paddock.repoRoot")))
    private val fixture = File(root, "fixtures/herdr-0.9.1/session-list.json").readText()

    @Test
    fun decodesTheCapturedCatalog() {
        val catalog = PaddockJson.decodeResult<SessionCatalog>(fixture).getOrThrow()
        val session = catalog.sessions.single()
        assertEquals("paddock-test", session.name)
        assertTrue(session.running)
        assertFalse(session.default)
        assertTrue(session.socketPath.endsWith("/sessions/paddock-test/herdr.sock"))
    }

    @Test
    fun unknownKeysAreIgnored() {
        val text = """{"sessions":[{"name":"a","default":false,"running":true,"session_dir":"/d","socket_path":"/s","future":1}],"extra":true}"""
        assertEquals("a", PaddockJson.decodeResult<SessionCatalog>(text).getOrThrow().sessions.single().name)
    }

    @Test
    fun aMissingRequiredFieldIsAFailureNotACrash() {
        val text = """{"sessions":[{"name":"a","default":false,"running":true,"session_dir":"/d"}]}"""
        assertTrue(PaddockJson.decodeResult<SessionCatalog>(text).isFailure)
    }

    @Test
    fun malformedJsonIsAFailureNotACrash() {
        assertTrue(PaddockJson.decodeResult<SessionCatalog>("{not json").isFailure)
    }
}
