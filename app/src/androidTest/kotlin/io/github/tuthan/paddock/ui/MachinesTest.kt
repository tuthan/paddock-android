package io.github.tuthan.paddock.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.ui.components.HostChip
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.screens.MachinesDialog
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The machine list behind the chip on Home (2026-10-06): watch another machine (Pro), remove one (Free), add another (Free). */
class MachinesTest {
    @get:Rule val rule = createComposeRule()

    private val machines = listOf(
        HostProfile("alpha", "Alpha laptop", "192.168.42.86", 22, "jdoe"),
        HostProfile("beta", "Beta server", "10.0.0.7", 2222, "ops", session = "work"),
        HostProfile("gamma", "Gamma", "gamma.lan", 22, "jdoe"),
    )

    private class Taps { val switched = mutableListOf<String>(); val removed = mutableListOf<String>(); var added = 0; var closed = 0 }

    private fun dialog(locked: Boolean, watched: String? = "alpha", fontScale: Float? = null, dark: Boolean = true): Taps {
        val taps = Taps()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    MachinesDialog(
                        MachineRoster.rows(machines, watched), locked,
                        onSwitch = { taps.switched += it.id }, onRemove = { taps.removed += it.id }, onAdd = { taps.added++ }, onClose = { taps.closed++ },
                    )
                }
            }
        }
        return taps
    }

    @Test fun everySavedMachineIsListedWithItsAddressAndSession() {
        dialog(locked = false)
        for (name in listOf("Alpha laptop", "Beta server", "Gamma")) rule.onNodeWithText(name).assertIsDisplayed()
        rule.onNodeWithText("jdoe@192.168.42.86:22 · default session").assertIsDisplayed()
        rule.onNodeWithText("ops@10.0.0.7:2222 · session work").assertIsDisplayed()
        rule.onNodeWithText(MachineCopy.INTRO).assertIsDisplayed()
    }

    @Test fun theWatchedMachineIsMarkedAndHasNoWatchButtonButCanBeRemoved() {
        dialog(locked = false, watched = "beta")
        rule.onAllNodesWithText(MachineCopy.WATCHING).assertCountEquals(1)
        rule.onNodeWithContentDescription("Watch Beta server").assertDoesNotExist()
        rule.onNodeWithContentDescription("Watch Alpha laptop").assertHasClickAction()
        rule.onNodeWithContentDescription("Watch Gamma").assertHasClickAction()
        rule.onNodeWithContentDescription("Remove Beta server…").assertHasClickAction()
    }

    @Test fun watchAndRemoveHandTheTappedRowToTheCaller() {
        val taps = dialog(locked = false)
        rule.onNodeWithContentDescription("Watch Gamma").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Remove Beta server…").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Remove Alpha laptop…").performScrollTo().performClick()
        assertEquals(listOf("gamma"), taps.switched)
        assertEquals(listOf("beta", "alpha"), taps.removed)
    }

    @Test fun withoutProEveryWatchControlSaysSoInItsOwnLabelAndRemoveDoesNot() {
        val taps = dialog(locked = true)
        rule.onAllNodesWithText("Watch · Pro").assertCountEquals(2)
        rule.onAllNodesWithText("Watch").assertCountEquals(0)
        rule.onNodeWithContentDescription("Watch Beta server · Pro").assertHasClickAction()
        rule.onAllNodesWithText("Remove…").assertCountEquals(3)
        rule.onNodeWithContentDescription("Remove Beta server…").performScrollTo().performClick()
        assertEquals(listOf("beta"), taps.removed)
        assertEquals(emptyList<String>(), taps.switched)
    }

    @Test fun aLockedWatchStillReachesTheCallerSoTheGateCanBeAsked() {
        val taps = dialog(locked = true)
        rule.onNodeWithContentDescription("Watch Gamma · Pro").performScrollTo().performClick()
        assertEquals(listOf("gamma"), taps.switched)
    }

    @Test fun addAnotherMachineAndCloseAreAlwaysThere() {
        val taps = dialog(locked = true)
        rule.onNodeWithText("Add another machine").performScrollTo().performClick()
        rule.onNodeWithText("Close").performScrollTo().performClick()
        assertEquals(1, taps.added)
        assertEquals(1, taps.closed)
    }

    @Test fun whileARemovalRunsWatchAndRemoveWaitButAddAndCloseDoNot() {
        rule.setContent {
            PaddockTheme(darkTheme = true) { MachinesDialog(MachineRoster.rows(machines, "alpha"), switchLocked = false, onSwitch = {}, onRemove = {}, onAdd = {}, onClose = {}, busy = true) }
        }
        rule.onNodeWithContentDescription("Watch Gamma").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Remove Gamma…").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Remove Alpha laptop…").assertIsNotEnabled()
        rule.onNodeWithText("Close").performScrollTo().assertIsEnabled()
    }

    @Test fun everythingIsReachableAtTwiceTheFontSize() {
        val taps = dialog(locked = false, fontScale = 2f)
        rule.onNodeWithContentDescription("Remove Gamma…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Watch Gamma").performScrollTo().performClick()
        rule.onNodeWithText("Close").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("gamma"), taps.switched)
    }

    @Test fun auditDarkAndLight() {
        dialog(locked = false)
        SemanticsAudit.expectClean(rule, "Machines, dark")
    }

    @Test fun auditLockedLight() {
        dialog(locked = true, dark = false)
        SemanticsAudit.expectClean(rule, "Machines, locked, light")
    }

    // ---- the chip -------------------------------------------------------------------------------------------------------------------

    @Test fun theChipOpensTheListOnlyWhenItIsGivenSomewhereToGo() {
        var opened = 0
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column {
                    HostChip("Plain", "live · 3 s", health = HostHealth.Live)
                    HostChip("Openable", "live · 3 s", health = HostHealth.Live, onClick = { opened++ })
                }
            }
        }
        rule.onNode(hasContentDescription("Plain, live, live · 3 s")).assertIsDisplayed().assertHasNoClickAction()
        rule.onNode(hasContentDescription("Openable, live, live · 3 s")).assertHasClickAction().performClick()
        assertEquals(1, opened)
    }
}
