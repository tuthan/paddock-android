package io.github.tuthan.paddock.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reduce motion (AC-10.3): the app has no animation that runs without a user action or a state change, so "Remove animations" has nothing to
 * stop, and the state dot, which a design could have made breathe, is a static canvas. This pins both facts by reading the sources: an infinite
 * animation, a view or framework animator, or any finite animation that is not on the list below fails the build until it is looked at.
 * The finite ones on the list run on Compose's own clock, which the platform's animator scale (Settings > Accessibility > Remove animations)
 * already scales to zero, so they finish at once with that setting on.
 */
class MotionTest {
    private val sources: List<Pair<String, String>> = listOf(File("src/main/kotlin"), File("../core/src/main/kotlin")).filter { it.exists() }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.path.removePrefix("src/main/kotlin/").removePrefix("../core/src/main/kotlin/").removePrefix("io/github/tuthan/paddock/") to it.readText() }.toList() }

    private fun uses(pattern: Regex) = sources.filter { pattern.containsMatchIn(it.second) }.map { it.first }.sorted()

    @Test fun nothingAnimatesForever() {
        val infinite = Regex("""rememberInfiniteTransition|infiniteRepeatable|InfiniteTransition|RepeatMode\.|repeatCount|REPEAT_INFINITE|Animation\.INFINITE""")
        assertEquals("an infinite animation needs a reduce-motion path: ${uses(infinite)}", emptyList<String>(), uses(infinite))
    }

    @Test fun noViewOrFrameworkAnimatorIsUsed() {
        val framework = Regex("""ValueAnimator|ObjectAnimator|AnimatorSet|AnimationUtils|LayoutTransition|animateLayoutChanges|overridePendingTransition|ViewPropertyAnimator|\.animate\(\)""")
        assertEquals("framework animators ignore nothing the app can control: ${uses(framework)}", emptyList<String>(), uses(framework))
    }

    @Test fun theOnlyAnimationsAreTheTwoFiniteOnesThatAreKnown() {
        // Finite, started by a user action or a change in the list, and scaled to zero by the platform's animator scale.
        val allowed = mapOf(
            "animateItem" to setOf("ui/screens/HerdHome.kt"),         // a row moves or fades when the herd's list changes; the order never changes under a finger
            "animateDpAsState" to setOf("ui/components/FormComponents.kt"),   // the switch thumb, 150 ms, after a tap
        )
        val any = Regex("""\b(animate\w*AsState|animateItem|animateContentSize|animateFloat|animateColor|animateValueAsState|AnimatedVisibility|AnimatedContent|Crossfade|Animatable|updateTransition|rememberTransition|Transition\b|withFrameNanos|withFrameMillis)\b""")
        val seen = sources.flatMap { (path, text) -> any.findAll(text).map { it.value to path }.toList() }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        for ((api, files) in seen) {
            val ok = allowed[api]
            assertTrue("$api is used in $files and is not on the reviewed list of finite animations (a new animation needs a reduce-motion decision)", ok != null && files.all { it in ok })
        }
    }

    @Test fun theStateDotIsAStaticCanvas() {
        val components = sources.first { it.first == "ui/components/Components.kt" }.second
        val dot = components.substringAfter("fun StateDot(").substringBefore("\n}\n")
        assertTrue("StateDot draws once from its inputs", "Canvas(" in dot)
        assertTrue("StateDot has no animation state", !Regex("""animate|Animatable|Transition|LaunchedEffect|withFrame""").containsMatchIn(dot))
    }
}
