package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.net.EndpointClass
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

    @Test fun theSessionNameIsOptionalAndCheckedWhenGiven() {
        assertNull(AddMachineForm.errors(input()).session)
        assertNull(AddMachineForm.profile(input(), emptySet())!!.session)
        assertNull(AddMachineForm.errors(AddMachineInput("box", "22", "jdoe", session = "paddock-test")).session)
        assertEquals("paddock-test", AddMachineForm.profile(AddMachineInput("box", "22", "jdoe", session = " paddock-test "), emptySet())!!.session)
        for (bad in listOf("a b", "-x", "a/b", "x;y")) assertNotNull(AddMachineForm.errors(AddMachineInput("box", "22", "jdoe", session = bad)).session, bad)
        assertNull(AddMachineForm.profile(AddMachineInput("box", "22", "jdoe", session = "a b"), emptySet()))
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

    @Test fun aNameThatResolvesToTheLanIsDescribedAsLocal() {
        assertEquals(RouteNote.LocalNeedsGrant, AddMachineForm.route("nas.lan", GateDecision.NeedsGrant, EndpointClass.Local))
        assertEquals(RouteNote.LocalReady, AddMachineForm.route("nas.lan", GateDecision.Granted, EndpointClass.Local))
        assertEquals(RouteNote.NotLocal, AddMachineForm.route("nas.lan", GateDecision.NotRequired, EndpointClass.NotLocal))
    }

    private fun profile(id: String, host: String = "192.168.1.20", port: Int = 22, user: String = "jdoe", wake: io.github.tuthan.paddock.wake.WakeTarget? = null, name: String = host) =
        HostProfile(id = id, name = name, host = host, port = port, user = user, wake = wake)

    @Test fun addingTheSameMachineAgainKeepsTheOneProfileItsNameAndItsWake() {
        val wake = io.github.tuthan.paddock.wake.WakeTarget(available = true, mac = "02:00:5e:10:00:01", iface = "wlp0s20f3", capturedAtMillis = 5)
        val have = profile("laptop", wake = wake, name = "Laptop")
        val again = AddMachineForm.resolve(input(), listOf(have))!!
        assertEquals("laptop", again.id)
        assertEquals("Laptop", again.name)
        assertEquals(wake, again.wake)
    }

    @Test fun theHostIsComparedWithoutCase() {
        val again = AddMachineForm.resolve(input(host = "Box.Local"), listOf(profile("box-local", host = "box.local")))!!
        assertEquals("box-local", again.id)
    }

    @Test fun aDifferentPortOrUserIsANewProfile() {
        val have = listOf(profile("192-168-1-20"))
        assertEquals("192-168-1-20-2", AddMachineForm.resolve(input(port = "2222"), have)!!.id)
        assertEquals("192-168-1-20-2", AddMachineForm.resolve(input(user = "other"), have)!!.id)
        assertNull(AddMachineForm.resolve(input(port = "2222"), have)!!.wake)
    }

    @Test fun aNameFromTheFinderRenamesTheKeptProfile() {
        val have = profile("a", name = "old")
        assertEquals("devbox", AddMachineForm.resolve(input().copy(name = " devbox "), listOf(have))!!.name)
        assertEquals("old", AddMachineForm.resolve(input().copy(name = "  "), listOf(have))!!.name)
    }

    @Test fun aNewMachineUsesTheFinderNameOrTheHost() {
        assertEquals("devbox", AddMachineForm.profile(input().copy(name = "devbox"), emptySet())!!.name)
        assertEquals("192.168.1.20", AddMachineForm.profile(input(), emptySet())!!.name)
        assertEquals(60, AddMachineForm.profile(input().copy(name = "n".repeat(90)), emptySet())!!.name.length)
    }

    @Test fun fixingTheKeyKeepsTheEditedProfileEvenWhenTheUserChanges() {
        val editing = profile("a", user = "jdoe")
        val fixed = AddMachineForm.resolve(input(user = "other"), listOf(editing, profile("b", host = "other.box")), replacing = editing)!!
        assertEquals("a", fixed.id)
        assertEquals("other", fixed.user)
    }

    @Test fun editingToAnotherHostMakesANewProfileAndLeavesTheOldOne() {
        val editing = profile("a")
        assertEquals("10-0-0-9", AddMachineForm.resolve(input(host = "10.0.0.9"), listOf(editing), replacing = editing)!!.id)
    }

    @Test fun invalidInputResolvesToNothing() = assertNull(AddMachineForm.resolve(input(user = ""), emptyList()))
}
