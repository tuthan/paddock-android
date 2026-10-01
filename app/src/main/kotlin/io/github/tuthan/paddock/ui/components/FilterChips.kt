package io.github.tuthan.paddock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** A single-choice filter row. The chips wrap onto more lines at large font sizes instead of clipping or scrolling away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .heightIn(min = PaddockTokens.spacing.touchTarget)
                    .background(if (on) c.field else c.surface, shape)
                    .border(1.dp, if (on) c.washBorder(c.accent) else c.line(), shape)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(i) })
                    .semantics { this.selected = on }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = PaddockTokens.type.secondary, color = if (on) c.title else c.dim) }
        }
    }
}
