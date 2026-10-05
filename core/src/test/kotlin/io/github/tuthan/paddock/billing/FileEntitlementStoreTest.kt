package io.github.tuthan.paddock.billing

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.elementNames

class FileEntitlementStoreTest {
    private val dir = createTempDirectory("entitlement").toFile().also { it.deleteOnExit() }
    private val file = File(dir, "nested/entitlement.json")

    @Test
    fun missingFileIsUnknown() = runBlocking<Unit> { assertEquals(EntitlementState(), FileEntitlementStore(file).load()) }

    @Test
    fun theStateSurvivesANewInstance() = runBlocking<Unit> {
        val state = EntitlementState(ProStatus.REVOKED, 123, acknowledged = false, purchaseTimeMillis = 99, revokeReason = RevokeReason.UNCONFIRMED)
        FileEntitlementStore(file).save(state)
        assertEquals(state, FileEntitlementStore(file).load())
    }

    @Test
    fun noPurchaseTokenIsWritten() = runBlocking<Unit> {
        FileEntitlementStore(file).save(EntitlementState(ProStatus.PRO, 5, true, 4))
        assertFalse("token" in file.readText().lowercase())
        assertEquals(setOf("status", "verifiedAtMillis", "acknowledged", "purchaseTimeMillis", "revokeReason"), EntitlementState.serializer().descriptor.elementNames.toSet())
    }

    @Test
    fun anUnreadableFileIsKeptAsideAndTheStateIsUnknownUntilTheNextVerification() = runBlocking<Unit> {
        file.parentFile.mkdirs()
        file.writeText("{ not json")
        assertEquals(EntitlementState(), FileEntitlementStore(file).load())
        assertTrue(File(file.absolutePath + ".corrupt").exists())
        assertFalse(file.exists())
    }

    @Test
    fun unknownKeysFromANewerVersionAreIgnored() = runBlocking<Unit> {
        file.parentFile.mkdirs()
        file.writeText("""{"status":"PRO","verifiedAtMillis":7,"acknowledged":true,"future":1}""")
        val s = FileEntitlementStore(file).load()
        assertEquals(ProStatus.PRO, s.status)
        assertEquals(7L, s.verifiedAtMillis)
    }
}
