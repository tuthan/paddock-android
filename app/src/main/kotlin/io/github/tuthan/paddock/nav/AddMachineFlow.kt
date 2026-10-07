package io.github.tuthan.paddock.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.tuthan.paddock.host.ConnectAttempt
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.hostprofile.PairingLink

/**
 * Everything that is held above the Add machine form and Welcome, so that it outlives them: a Connect that has not finished, the pairing link or the
 * finder's pick waiting for the form, and the choices made on Welcome. Saved with [NavState], except [found], which is only ever a hand-off.
 */
internal class AddMachineFlow {
    /** Add machine opened to set up the watched machine's key (its key could not be read), not to add another. */
    var editing by mutableStateOf(false)
    /** Opened from Home's "Show the command": the intro leads with how to authorize this phone. */
    var authorizeIntro by mutableStateOf(false)
    /** The first screen of a phone with no machine is Welcome; any way in sets this and moves on to Add machine. */
    var welcomeChoice by mutableStateOf(false)
    /** Find on this network opened from Welcome (from Add machine it is opened inside the form's own route, which keeps what was typed). */
    var welcomeFinder by mutableStateOf(false)
    /** The scanner opened from Welcome (from Add machine it is opened inside the form's route, like the finder). */
    var welcomeScan by mutableStateOf(false)
    /** A pairing link (a tap, or Paste a pairing link): Add machine opens pre-filled. Nothing connects and nothing is trusted from a link. */
    var pairing by mutableStateOf<PairingLink?>(null)
    /** A Connect that has not finished. Above the screens: saving the first machine ends "no machines" and must not take the form away mid-attempt. */
    var attempt by mutableStateOf<ConnectAttempt?>(null)
    /** A machine the finder found, waiting for the form to take it. Taken once, then cleared. */
    var found by mutableStateOf<AddMachineInput?>(null)
}
