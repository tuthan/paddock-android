package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.net.GateDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddMachineFormTest {
    private fun input(host: String = "192.168.1.20", port: String = "22", user: String = "jdoe") = AddMachineInput(host, port, user)

    @Test fun aGoodFormHasNoErrorsAndBuildsAProfile() {
        assertEquals(FieldErrors(), AddMachineForm.errors(input()))
        val p = AddMachineForm.profile(input(), emptySet())!!
        assertEquals("192-168-1-20", p.id)
        assertEquals("192.168.1.20", p.host)
        assertEquals(KeyKind.Phone, p.key)
    }

    @Test fun eachFieldGetsItsOwnMessage() {
        val e = AddMachineForm.errors(AddMachineInput(host = "", port = "", user = ""))
        assertEquals("Enter a hostname or IP address.", e.host)
        assertEquals("Enter a port from 1 to 65535.", e.port)
        assertEquals("Enter the user name to sign in as.", e.user)
    }

    @Test fun urlsAndUserAtHostAreExplainedNotJustRejected() {
        for (bad in listOf("ssh://box", "jdoe@box", "box/path", "http://x")) {
            assertTrue(AddMachineForm.errors(input(host = bad)).host!!.contains("just the host"), bad)
        }
    }

    @Test fun spacesAndOddCharactersAreRejected() {
        assertEquals("A host cannot contain spaces.", AddMachineForm.errors(input(host = "my box")).host)
        assertNotNull(AddMachineForm.errors(input(host = "-oFoo")).host)
        assertNotNull(AddMachineForm.errors(input(host = "a;b")).host)
    }

    @Test fun portsMustBeNumbersInRange() {
        for (bad in listOf("0", "65536", "ssh", "-1", "22.5")) assertNotNull(AddMachineForm.errors(input(port = bad)).port, bad)
        assertNull(AddMachineForm.errors(input(port = " 2222 ")).port)
        assertEquals(2222, AddMachineForm.profile(input(port = " 2222 "), emptySet())!!.port)
    }

    @Test fun usersAreCheckedAgainstTheProfileRule() {
        assertNotNull(AddMachineForm.errors(input(user = "bad user")).user)
        assertNull(AddMachineForm.errors(input(user = "svc_deploy-1")).user)
    }

    @Test fun anIpv6LiteralInBracketsIsAccepted() {
        assertNull(AddMachineForm.errors(input(host = "[fe80::1%wlan0]")).host)
        assertEquals("fe80::1%wlan0", AddMachineForm.profile(input(host = "[fe80::1%wlan0]"), emptySet())!!.host)
    }

    @Test fun idsAreSlugsAndStayUniqueAgainstExistingProfiles() {
        assertEquals("box-example-ts-net", AddMachineForm.idFor("Box.Example.ts.net", emptySet()))
        assertEquals("laptop-2", AddMachineForm.idFor("laptop", setOf("laptop")))
        assertEquals("laptop-3", AddMachineForm.idFor("laptop", setOf("laptop", "laptop-2")))
        assertEquals("machine", AddMachineForm.idFor("...", emptySet()))
        assertTrue(AddMachineForm.idFor("a".repeat(100), emptySet()).length <= 41)
        assertTrue(HostProfile.ID.matches(AddMachineForm.idFor("-weird-.name", emptySet())))
    }

    @Test fun anImportedKeyProfileCarriesItsKeyId() {
        val p = AddMachineForm.profile(AddMachineInput("box", "22", "jdoe", KeyKind.Imported, "box"), emptySet())!!
        assertEquals("box", p.importedKeyId)
    }

    @Test fun aProfileIsNullWhileAnythingIsWrong() = assertNull(AddMachineForm.profile(input(user = ""), emptySet()))

    @Test fun theRouteNoteDependsOnTheAddressAndTheGrant() {
        assertEquals(RouteNote.Empty, AddMachineForm.route("", GateDecision.NotRequired))
        assertEquals(RouteNote.NotLocal, AddMachineForm.route("100.101.102.103", GateDecision.NotRequired)) // tailnet
        assertEquals(RouteNote.NotLocal, AddMachineForm.route("box.example.ts.net", GateDecision.NeedsGrant)) // a name is not local
        assertEquals(RouteNote.LocalReady, AddMachineForm.route("192.168.1.20", GateDecision.Granted))
        assertEquals(RouteNote.LocalReady, AddMachineForm.route("192.168.1.20", GateDecision.NotRequired))
        assertEquals(RouteNote.LocalNeedsGrant, AddMachineForm.route("192.168.1.20", GateDecision.NeedsGrant))
        assertEquals(RouteNote.LocalNeedsGrant, AddMachineForm.route("[fe80::1]", GateDecision.NeedsGrant))
        assertEquals(RouteNote.LocalNeedsGrant, AddMachineForm.route("printer.local", GateDecision.NeedsGrant))
    }
}
