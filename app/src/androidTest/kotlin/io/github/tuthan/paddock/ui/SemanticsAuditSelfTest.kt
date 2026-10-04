package io.github.tuthan.paddock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The audit has to be able to fail: each rule is fed a screen that breaks it, and a good screen must come back clean. */
class SemanticsAuditSelfTest {
    @get:Rule val rule = createComposeRule()

    @Test fun everyRuleFiresOnAScreenThatBreaksIt() {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().background(PaddockTokens.colors.ground)) {
                    Box(Modifier.size(30.dp).clickable { })                                                        // no label, and 30 dp
                    Text("faint words nobody can read", color = Color(0xFF3A3F5C))                                  // under 4.5:1 on the ground
                    Box(Modifier.size(60.dp).semantics { role = Role.Switch; contentDescription = "Alerts" }.clickable { })   // a switch that does not say on or off
                    Box(Modifier.size(60.dp).semantics { role = Role.RadioButton; contentDescription = "Choice" }.clickable { }) // a radio that does not say selected
                    Box(Modifier.size(80.dp).semantics { contentDescription = "Outer" }.clickable { }) { Box(Modifier.size(80.dp).semantics { contentDescription = "Inner" }.clickable { }) } // two on one rectangle
                }
            }
        }
        val r = SemanticsAudit.run(rule, "self-test")
        val rules = r.findings.map { it.rule }.toSet()
        for (expected in listOf("label", "target", "contrast", "state", "duplicate", "heading")) assertTrue("rule '$expected' did not fire: ${r.findings}", expected in rules)
    }

    @Test fun overflowFiresOnTextThatIsCutOff() {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().background(PaddockTokens.colors.ground)) {
                    Text("a long sentence that cannot fit on one line at all", maxLines = 1, color = PaddockTokens.colors.text, modifier = Modifier.size(60.dp, 20.dp).semantics { heading() })
                }
            }
        }
        val r = SemanticsAudit.run(rule, "overflow self-test", SemanticsAudit.Options(overflow = true))
        assertTrue(r.findings.toString(), r.findings.any { it.rule == "overflow" })
    }

    @Test fun aRegionSqueezedByItsSiblingsIsCollapsedEvenWhenItsContentIsTall() {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().background(PaddockTokens.colors.ground)) {
                    // 300 dp of content in a 40 dp window: what a user can see of it is 40 dp.
                    Box(Modifier.height(40.dp).clipToBounds()) { Box(Modifier.testTag("region").fillMaxWidth().height(300.dp)) }
                    Text("Title", color = PaddockTokens.colors.title, modifier = Modifier.semantics { heading() })
                }
            }
        }
        val r = SemanticsAudit.run(rule, "collapsed self-test", SemanticsAudit.Options(regions = mapOf("region" to 100f)))
        assertTrue(r.findings.toString(), r.findings.any { it.rule == "collapsed" })
    }

    @Test fun aGoodScreenIsClean() {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                Column(Modifier.fillMaxWidth().background(PaddockTokens.colors.ground)) {
                    Text("Title", color = PaddockTokens.colors.title, modifier = Modifier.semantics { heading() })
                    Text("Some body text in the dim colour", color = PaddockTokens.colors.dim)
                    Box(Modifier.heightIn(min = 48.dp).size(120.dp, 48.dp).semantics { contentDescription = "Open" ; role = Role.Button }.clickable { }) { Text("Open", color = PaddockTokens.colors.text) }
                }
            }
        }
        val r = SemanticsAudit.run(rule, "good", SemanticsAudit.Options(overflow = true))
        assertEquals(emptyList<SemanticsAudit.Finding>(), r.findings)
        assertTrue("it measured the text it saw: ${r.contrast}", r.contrast.size >= 2)
    }
}
