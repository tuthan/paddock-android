package io.github.tuthan.paddock.demo

import io.github.tuthan.paddock.hostprofile.HostProfile

/**
 * The demo machine (Phase 16, slice 3): a herdr session that exists only in memory, for a person who has no machine to point Paddock at yet,
 * and for the reviewer of a store listing. It is a machine in every way the screens see ([DemoSession] answers what herdr and the relay would),
 * and it opens no socket, writes no profile, asks for no permission. See docs/demo.md.
 */
object Demo {
    /** What the demo's profile is called. A host the form cannot produce ([HOST] is a reserved name that never resolves), so no saved machine is ever mistaken for it. */
    const val PROFILE_ID = "demo-invalid"
    const val HOST = "demo.invalid"
    const val NAME = "Demo machine"
    const val USER = "demo"

    /** The herdr session the demo machine runs. */
    const val SESSION = "demo"

    /** What the machine "is": the places the app looks for herdr, its relay and its home, and what `uname` says. */
    const val HERDR_PATH = "/usr/local/bin/herdr"
    const val HOME = "/home/demo"
    const val SESSION_DIR = "$HOME/.config/herdr/sessions/$SESSION"
    const val SOCKET_PATH = "$SESSION_DIR/herdr.sock"
    const val RELAY_PATH = "$HOME/.local/share/paddock/paddock-relay.py"
    const val HOSTNAME = "demo"
    const val HERDR_VERSION = "0.9.1"
    const val PROTOCOL = 22

    val profile = HostProfile(id = PROFILE_ID, name = NAME, host = HOST, user = USER)

    fun isDemo(profile: HostProfile) = profile.host == HOST
    fun isDemo(profileId: String) = profileId == PROFILE_ID
}

/**
 * The words the demo says where a part of Paddock needs a real machine. In place, in the sentence style of docs/buttons.md: a short sentence that says
 * what is missing, never a button that quietly does nothing.
 */
object DemoCopy {
    /** The entry on Welcome is the button "Try a demo" (the literal is in the screen, where `ButtonRolesTest` pins its kind); this is what a screen reader adds to it. */
    const val ENTRY_DESCRIPTION = "Look around with a demo machine"

    /** The About row where a real machine shows the herdr version it runs. */
    const val ABOUT_HOST = "demo machine"

    /** A fixed note on Home while the demo runs, so nobody takes it for a real machine. */
    const val BANNER = "This is a demo machine. Nothing here runs on a computer."

    const val NOT_IN_DEMO = "Not in the demo. It needs your own machine."

    fun notInDemo(what: String) = "$what is not in the demo. It needs your own machine."

    /** The refusals the demo machine's herdr and relay send for what it does not do; shown by the screens as herdr's own answer. */
    const val REFUSAL_CODE = "not_in_demo"
    const val REFUSAL = "Not in the demo. It needs your own machine."

    const val WAKE = "Waking a machine is not in the demo. It needs your own machine."
    const val ALERTS = "Alert setup is not in the demo. It needs your own machine."
    const val GUARDED_ANSWERS = "Guarded answers are not in the demo. They need your own machine."
    const val FILES = "Sending and fetching files is not in the demo. It needs your own machine."
    const val DROP_FOLDER = "The drop folder is not in the demo. It needs your own machine."
    const val ADDRESS = "Changing the address is not in the demo. It needs your own machine."
    const val FIND = "Finding machines on this network is not in the demo."
    const val PAIRING = "Pairing with a desktop is not in the demo."
    const val CONTROL = "Taking control of a terminal is not in the demo. It needs your own machine."
    const val START_AGENT = "Starting an agent is not in the demo. It needs your own machine."
    const val SPACES = "Stopping or deleting a session is not in the demo. It needs your own machine."
}
