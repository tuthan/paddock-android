package io.github.tuthan.paddock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * A single-choice filter row of chips, 34 dp to the eye with a 48 dp target. They wrap onto more lines at large font
 * sizes instead of clipping or scrolling away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    FlowRow(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(50)
            // The 48 dp target is the outer box; the press ripple is drawn on the visible chip only.
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier.heightIn(min = PaddockTokens.spacing.touchTarget)
                    .selectable(selected = on, interactionSource = press, indication = null, role = Role.RadioButton, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.heightIn(min = 34.dp).clip(shape).indication(press, ripple()).background(if (on) c.accent.copy(alpha = 0.14f) else c.surface)
                        .border(1.dp, if (on) c.accent.copy(alpha = 0.5f) else c.control(), shape).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = PaddockTokens.type.chip, color = if (on) c.title else c.text) }
            }
        }
    }
}
