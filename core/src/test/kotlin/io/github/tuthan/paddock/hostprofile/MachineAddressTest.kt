package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.billing.GateContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A new address typed on a machine's page: the Add machine form's rules for host and port, the address it already has, and another machine's. */
class MachineAddressTest {
    private val laptop = HostProfile("laptop", "Laptop", "192.168.42.86", 22, "jdoe")
    private val studio = HostProfile("studio", "Studio", "192.168.42.90", 22, "jdoe")
    private val all = listOf(laptop, studio)

    @Test fun aNewAddressIsTrimmedAndItsPortRead() {
        assertEquals(MachineAddress.Result.Ok("192.168.42.120", 22), MachineAddress.check("  192.168.42.120 ", " 22 ", laptop, all))
        assertEquals(MachineAddress.Result.Ok("devbox.local", 2222), MachineAddress.check("devbox.local", "2222", laptop, all))
        assertEquals(MachineAddress.Result.Ok("fe80::1%wlan0", 22), MachineAddress.check("[fe80::1%wlan0]", "22", laptop, all), "brackets go, as on Add machine")
        assertEquals(MachineAddress.Result.Ok("192.168.42.86", 2200), MachineAddress.check("192.168.42.86", "2200", laptop, all), "the port alone is a move")
    }

    @Test fun theAddressItAlreadyHasIsUnchangedWhateverItsCase() {
        assertEquals(MachineAddress.Result.Unchanged, MachineAddress.check("192.168.42.86", "22", laptop, all))
        val named = laptop.copy(host = "Devbox.local")
        assertEquals(MachineAddress.Result.Unchanged, MachineAddress.check("devbox.LOCAL", "22", named, listOf(named)))
    }

    @Test fun aBadHostOrPortSaysWhyInTheFormsOwnWords() {
        val badHost = MachineAddress.check("ssh://box", "22", laptop, all)
        assertEquals("Enter just the host, like 192.168.1.20 or box.example.ts.net. The user has its own field.", MachineAddress.hostMessage(badHost))
        assertNull(MachineAddress.portMessage(badHost))
        assertEquals("Enter a hostname or IP address.", MachineAddress.hostMessage(MachineAddress.check("  ", "22", laptop, all)))
        assertEquals("A host cannot contain spaces.", MachineAddress.hostMessage(MachineAddress.check("my box", "22", laptop, all)))
        val badPort = MachineAddress.check("192.168.42.120", "70000", laptop, all)
        assertNull(MachineAddress.hostMessage(badPort))
        assertEquals("Enter a port from 1 to 65535.", MachineAddress.portMessage(badPort))
        assertTrue(MachineAddress.check("192.168.42.120", "", laptop, all) is MachineAddress.Result.Bad)
    }

    @Test fun anotherSavedMachinesAddressIsRefusedForTheSameUserOnly() {
        val taken = MachineAddress.check("192.168.42.90", "22", laptop, all)
        assertEquals(MachineAddress.Result.Taken("Studio"), taken)
        assertNotNull(MachineAddress.hostMessage(taken)).let { assertTrue(it.startsWith("Studio already has this address.")) }
        assertEquals(MachineAddress.Result.Taken("Studio"), MachineAddress.check("192.168.42.90", "22", laptop, listOf(laptop, studio.copy(host = "192.168.42.90"))))
        // Another user on the same host is another sign-in, and so another saved machine: the Add form keeps those apart too.
        val otherUser = studio.copy(user = "build")
        assertEquals(MachineAddress.Result.Ok("192.168.42.90", 22), MachineAddress.check("192.168.42.90", "22", laptop, listOf(laptop, otherUser)))
        assertEquals(MachineAddress.Result.Ok("192.168.42.90", 2222), MachineAddress.check("192.168.42.90", "2222", laptop, all), "another port is another address")
    }

    @Test fun onlyTheWatchedMachineWaitsAndOnlyForAnOperationInFlight() {
        assertTrue(MachineAddress.waits(watched = true, GateContext.OPERATION_IN_FLIGHT))
        for (context in GateContext.entries - GateContext.OPERATION_IN_FLIGHT) assertFalse(MachineAddress.waits(watched = true, context), "$context")
        for (context in GateContext.entries) assertFalse(MachineAddress.waits(watched = false, context), "another machine: $context")
    }
}
