package io.github.tuthan.paddock.alerts

import kotlin.test.Test
import kotlin.test.assertEquals

/** The relay screen and the machine page say one machine's UnifiedPush state in the same words. */
class PushWordsTest {
    private fun reg(endpoint: String? = null, shared: Long? = null, failure: PushFailure? = null) =
        PushRegistration("laptop", "tok", "io.heckel.ntfy", 1L, endpoint = endpoint, sharedAtMillis = shared, failure = failure)

    @Test fun eachStateHasItsSentence() {
        assertEquals("The address is on Laptop (host ntfy.sh).", PushWords.state(true, true, false, "Laptop", endpointHost = "ntfy.sh"))
        assertEquals("The address was sent, but the file is not on Laptop now. Send it again.", PushWords.state(true, true, false, "Laptop", onHost = false))
        assertEquals("The distributor gave this phone an address (host ntfy.sh). It is not on Laptop yet.", PushWords.state(true, false, false, "Laptop", endpointHost = "ntfy.sh"))
        assertEquals("Registration failed. Register again.", PushWords.state(false, false, true, "Laptop"))
        assertEquals("Waiting for the distributor's address…", PushWords.state(false, false, false, "Laptop"))
    }

    @Test fun theMachinePageReadsTheRegistrationAndNothingOnTheHost() {
        assertEquals(PushWords.NOT_REGISTERED, PushWords.state(null, "Laptop"))
        assertEquals("The address is on Laptop.", PushWords.state(reg(endpoint = "https://ntfy.sh/up1", shared = 5L), "Laptop"))
        assertEquals("The distributor gave this phone an address. It is not on Laptop yet.", PushWords.state(reg(endpoint = "https://ntfy.sh/up1"), "Laptop"))
        assertEquals(PushWords.FAILED, PushWords.state(reg(failure = PushFailure.Network), "Laptop"))
        assertEquals(PushWords.WAITING, PushWords.state(reg(), "Laptop"))
    }
}
