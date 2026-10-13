package io.github.tuthan.paddock.routes

import io.github.tuthan.paddock.files.ShareDraft
import io.github.tuthan.paddock.files.SharedItem
import io.github.tuthan.paddock.files.Snapshot
import io.github.tuthan.paddock.ops.AgentOperations
import io.github.tuthan.paddock.ops.DraftAttachment
import io.github.tuthan.paddock.ops.PromptDraft
import io.github.tuthan.paddock.ui.screens.ShareReplace
import io.github.tuthan.paddock.ui.screens.ShareReplaceCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What choosing an agent for a share does to the one prompt draft the app holds ([shareToAgent]): [ShareDraft]'s rule, that a share never loses or
 * overwrites what the person wrote, holds for another agent's draft too. That draft is replaced only after the person said Replace.
 */
class ShareToAgentTest {
    private val theirFile = DraftAttachment("snap-x", "2026-10-11-140000-notes.txt", 12, "text/plain")
    private val snapshot = Snapshot("snap-new", "2026-10-11-143205-shot.png", "image/png", 84_000, "0".repeat(64))
    private val newFile = DraftAttachment(snapshot.id, snapshot.name, snapshot.size, snapshot.mime)
    private val text = SharedItem.Text("https://example.org/issue/42")
    private val file = SharedItem.File(snapshot, text = "see this")

    @Test fun aShareToAnotherAgentAsksFirstWhenTheDraftHoldsWordsOrAFile() {
        val both = PromptDraft("x", "half a thought", attachment = theirFile)
        assertEquals(ShareToAgent.Ask("x", words = true, file = true), shareToAgent(both, "y", text, confirmed = false))
        assertEquals(ShareToAgent.Ask("x", words = true, file = true), shareToAgent(both, "y", file, confirmed = false))
        assertEquals(ShareToAgent.Ask("x", words = true, file = false), shareToAgent(PromptDraft("x", "half a thought"), "y", text, confirmed = false))
        assertEquals(ShareToAgent.Ask("x", words = false, file = true), shareToAgent(PromptDraft("x", "", attachment = theirFile), "y", file, confirmed = false))
    }

    @Test fun afterReplaceTheDraftIsTheChosenAgentsAndTheOtherAgentsFileIsLetGo() {
        val both = PromptDraft("x", "half a thought", attachment = theirFile)
        assertEquals(ShareToAgent.Add(text.text, null, dropped = theirFile.snapshotId), shareToAgent(both, "y", text, confirmed = true))
        assertEquals(ShareToAgent.Add("see this", newFile, dropped = theirFile.snapshotId), shareToAgent(both, "y", file, confirmed = true))
    }

    @Test fun theChosenAgentsOwnDraftIsMergedIntoAndNeverAskedAbout() {
        val own = PromptDraft("y", "half a thought", attachment = theirFile)
        assertEquals("a text keeps the file", ShareToAgent.Add("half a thought\n\n${text.text}", null, dropped = null), shareToAgent(own, "y", text, confirmed = false))
        // A draft holds one attachment: a shared file takes the place of the one it had, as before.
        assertEquals(ShareToAgent.Add("half a thought\n\nsee this", newFile, dropped = theirFile.snapshotId), shareToAgent(own, "y", file, confirmed = false))
    }

    @Test fun nothingToLoseIsNotAskedAbout() {
        assertEquals(ShareToAgent.Add(text.text, null, null), shareToAgent(PromptDraft(), "y", text, confirmed = false))
        assertEquals("blank words are nothing to lose", ShareToAgent.Add(text.text, null, null), shareToAgent(PromptDraft("x", "  \n"), "y", text, confirmed = false))
        // The draft's accepted rows say nothing about unsent words.
        assertEquals(ShareToAgent.Add(text.text, null, null), shareToAgent(PromptDraft("x", "", acceptedThrough = mapOf("x" to 3L)), "y", text, confirmed = false))
    }

    @Test fun overTheLimitTogetherChangesNothing() {
        val long = PromptDraft("y", "a".repeat(AgentOperations.MAX_PROMPT_CHARS - 5))
        assertEquals(ShareToAgent.TooLong, shareToAgent(long, "y", text, confirmed = false))
    }

    @Test fun theQuestionNamesTheAgentWhenItCanAndSaysWhatGoes() {
        val body = ShareReplaceCopy.body(ShareReplace("Fix the build", words = true, file = true))
        assertEquals("Paddock holds an unsent prompt and its file for Fix the build. Adding this share replaces both.", body)
        assertEquals("Paddock holds an unsent prompt for another agent. Adding this share replaces it.", ShareReplaceCopy.body(ShareReplace(null, words = true, file = false)))
        assertTrue(ShareReplaceCopy.body(ShareReplace(null, words = false, file = true)).startsWith("Paddock holds an unsent file for another agent."))
    }
}
