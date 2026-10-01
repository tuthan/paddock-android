package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.ledger.ActivityFilter
import io.github.tuthan.paddock.ledger.Activity
import io.github.tuthan.paddock.ledger.ActivityPresenter
import io.github.tuthan.paddock.ledger.ActivitySection
import io.github.tuthan.paddock.ledger.ActionKind
import io.github.tuthan.paddock.ledger.ActionOutcome
import io.github.tuthan.paddock.ledger.Observation
import io.github.tuthan.paddock.ledger.ObservationKind
import io.github.tuthan.paddock.ledger.PhoneAction
import io.github.tuthan.paddock.ui.screens.ActivityLog
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ActivityLogTest {
    @get:Rule val rule = createComposeRule()

    private fun ms(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val now = ms(1, 15, 0)
    private val presenter = ActivityPresenter(ZoneOffset.UTC, Locale.ENGLISH, { "Laptop" }, { _, _, t -> mapOf("term_a" to "approve edit to build.gradle", "term_b" to "write release notes")[t] })

    private fun obs(id: Long, kind: ObservationKind, at: Long, terminal: String? = null, detail: String = "") =
        Observation(id, "laptop", "main", terminal, 1, kind, at, detail)

    private val observations = listOf(
        obs(1, ObservationKind.StateChanged, ms(1, 0, 0) - 3_600_000, "term_b", "working -> done"),
        obs(2, ObservationKind.Disconnected, ms(1, 14, 0)),
        obs(3, ObservationKind.Connected, ms(1, 14, 6)),
        obs(5, ObservationKind.StateChanged, ms(1, 14, 50), "term_a", "working -> blocked"),
    )
    private val actions = listOf(PhoneAction(4, "laptop", "main", "term_b", 1, ActionKind.MarkSeen, ms(1, 14, 20), ActionOutcome.Ok))

    /** The real pipeline: the ledger's own builder, then the presenter. */
    private fun sections(filter: ActivityFilter = ActivityFilter.All): List<ActivitySection> =
        presenter.present(Activity.build(observations, actions, filter), now)

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(sections: List<ActivitySection>, filter: ActivityFilter = ActivityFilter.All, dark: Boolean = true, fontScale: Float? = null, onFilter: (ActivityFilter) -> Unit = {}) =
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) { ActivityLog(sections, filter, onFilter) }
            }
        }

    private fun byDesc(d: String) = rule.onNode(hasContentDescription(d))

    @Test fun daysAreHeadingsAndRowsReadTimeThenWhatHappened() {
        show(sections())
        rule.onNodeWithText("TODAY").assertIsDisplayed()
        byDesc("14:50, approve edit to build.gradle needed you, working → blocked").assertIsDisplayed()
        byDesc("14:20, You marked write release notes as seen").assertIsDisplayed()
        shoot("activity-all-dark-100")
    }

    @Test fun disconnectedTimeIsAGapRowThatSaysSo() {
        show(sections())
        byDesc("14:00, No connection to Laptop for 6 min, nothing was observed in this time").assertIsDisplayed()
    }

    @Test fun theClosingNoteSaysWhatTheListIsAndIsNot() {
        show(sections())
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("This is this phone's own record", substring = true))
        rule.onNodeWithText("This is this phone's own record of what it saw and did. Time with no connection shows as a gap.").assertIsDisplayed()
    }

    @Test fun noRowClaimsACauseItCannotKnow() {
        show(sections())
        val all = sections().flatMap { it.rows }.joinToString(" ") { it.text }
        assertTrue(!all.contains("answered", ignoreCase = true) && !all.contains("elsewhere", ignoreCase = true))
    }

    @Test fun filtersAreSingleChoiceTouchTargetsThatReportTheChoice() {
        var chosen: ActivityFilter? = null
        show(sections(), onFilter = { chosen = it })
        rule.onNodeWithText("All").assertIsSelected()
        rule.onNodeWithText("Connection").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(ActivityFilter.Connection, chosen)
    }

    @Test fun aFilteredListShowsOnlyItsKindAndItsChipIsSelected() {
        show(sections(ActivityFilter.PhoneActions), ActivityFilter.PhoneActions)
        rule.onNodeWithText("From this phone").assertIsSelected()
        byDesc("14:20, You marked write release notes as seen").assertIsDisplayed()
        shoot("activity-phone-actions-dark-100")
    }

    @Test fun anEmptyLedgerSaysWhyInsteadOfShowingNothing() {
        show(emptyList())
        rule.onNodeWithText("Nothing here yet.", substring = true).assertIsDisplayed()
    }

    @Test fun twoHundredPercentFontWrapsTheChipsAndNothingClips() {
        show(sections(), fontScale = 2f)
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        for (label in listOf("All", "State changes", "Connection", "From this phone")) {
            val b = rule.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("$label outside the screen: $b vs $root", b.left >= root.left && b.right <= root.right + 0.5.dp)
        }
        val time = rule.onNodeWithText("14:50", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the time wrapped onto two lines: $time", time.bottom - time.top < 48.dp)
        shoot("activity-all-dark-200")
    }

    @Test fun lightThemeRenders() {
        show(sections(), dark = false)
        rule.onNodeWithText("TODAY").assertIsDisplayed()
        shoot("activity-all-light-100")
    }
}
