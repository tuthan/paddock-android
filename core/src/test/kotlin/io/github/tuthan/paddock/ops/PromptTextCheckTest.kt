package io.github.tuthan.paddock.ops

import kotlin.test.Test
import kotlin.test.assertEquals

/** Looking for a prompt's text in the output: it describes the output only, and says when it cannot look at all. */
class PromptTextCheckTest {
    private fun record(text: String?) =
        OperationRecord(1, "h1", "paddock-test", "term_1", 1, OperationKind.Prompt, 1_000, OperationOutcome.Unknown, promptText = text, payloadSha256 = "ab".repeat(32))

    @Test fun aHashOnlyRowCannotBeLookedFor() {
        assertEquals(TextCheck.NotKept, PromptTextCheck.check(record(null), listOf("anything")))
        assertEquals(TextCheck.NotKept, PromptTextCheck.check(record("   \n "), listOf("anything")), "text that is only whitespace is no text")
    }

    @Test fun noOutputYetCannotBeLookedAt() {
        assertEquals(TextCheck.NoOutput, PromptTextCheck.check(record("rotate the key"), null))
    }

    @Test fun textThatAppearsIsFound() {
        assertEquals(TextCheck.Found, PromptTextCheck.check(record("rotate the key"), listOf("> rotate the key", "thinking…")))
    }

    @Test fun aTerminalsWrappingAndSpacingDoNotHideIt() {
        val text = "refactor the parser\nso that errors carry a span,   then run the tests"
        assertEquals(TextCheck.Found, PromptTextCheck.check(record(text), listOf("> refactor the parser so that", "  errors carry a span, then", "  run the tests")))
    }

    @Test fun textThatIsNotThereIsNotFoundAndThatIsAllItSays() {
        assertEquals(TextCheck.NotFound, PromptTextCheck.check(record("rotate the key"), listOf("> rotate", "the other key")))
        assertEquals(TextCheck.NotFound, PromptTextCheck.check(record("rotate the key"), emptyList()))
    }

    @Test fun itIsAnExactMatchNotAFuzzyOne() {
        assertEquals(TextCheck.NotFound, PromptTextCheck.check(record("Rotate the key"), listOf("rotate the key")), "case matters: nothing is guessed")
    }
}
