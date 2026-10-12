package io.github.tuthan.paddock.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Output feed polls only in the foreground and only on the Output tab. */
class OutputFeedVisibilityTest {
    @Test fun itPollsOnTheOutputTabInTheForeground() = assertTrue(outputFeedVisible(resumed = true, tab = AgentTab.Output))
    @Test fun itDoesNotPollUnderTheTerminalTab() = assertFalse(outputFeedVisible(resumed = true, tab = AgentTab.Terminal))
    @Test fun itDoesNotPollInTheBackgroundOnEitherTab() {
        for (tab in AgentTab.entries) assertFalse(outputFeedVisible(resumed = false, tab = tab))
    }

    @Test fun itDoesNotPollWhileTheSelectTextViewIsOpen() {
        assertFalse(outputFeedVisible(resumed = true, tab = AgentTab.Output, selecting = true))
        assertTrue("closing the view lets it poll again", outputFeedVisible(resumed = true, tab = AgentTab.Output, selecting = false))
    }
}
