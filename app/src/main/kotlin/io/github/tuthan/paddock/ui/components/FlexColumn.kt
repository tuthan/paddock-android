package io.github.tuthan.paddock.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

private object FlexChild

/** Marks the one child of a [FlexColumn] that takes the room the others leave. */
fun Modifier.flexible(): Modifier = this.then(object : ParentDataModifier { override fun Density.modifyParentData(parentData: Any?): Any = FlexChild })

/**
 * A column in which one child, marked [flexible], takes what the others leave, as `Modifier.weight(1f)` does, but never less than [flexMin].
 * When the other children plus that minimum are taller than the column, and [scrollable] is on, the column scrolls instead of squeezing the
 * flexible child to nothing and letting the children below it fall off the screen. On a screen with room it lays out exactly like a column with
 * a weighted child and never scrolls. This is what a large font on a small phone needs: the request on the decision sheet and the output on
 * the Output tab are the reason for the screen and must keep a height of their own.
 */
@Composable
fun FlexColumn(
    modifier: Modifier = Modifier,
    flexMin: Dp = 0.dp,
    spacing: Dp = 0.dp,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxHeight
        Layout(content, Modifier.fillMaxWidth().then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)) { measurables, c ->
            val gap = spacing.roundToPx()
            val loose = Constraints(minWidth = c.minWidth, maxWidth = c.maxWidth)
            val flexIndex = measurables.indexOfFirst { it.parentData === FlexChild }
            val fixed = measurables.mapIndexedNotNull { i, m -> if (i == flexIndex) null else i to m.measure(loose) }.toMap()
            val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
            val used = fixed.values.sumOf { it.height } + gaps
            val flex = if (flexIndex < 0) null else {
                val room = max(flexMin.roundToPx(), if (viewport == Constraints.Infinity) 0 else viewport - used)
                measurables[flexIndex].measure(Constraints(minWidth = c.minWidth, maxWidth = c.maxWidth, minHeight = room, maxHeight = room))
            }
            val total = used + (flex?.height ?: 0)
            val width = max(c.minWidth, (fixed.values + listOfNotNull(flex)).maxOfOrNull { it.width } ?: 0).coerceAtMost(c.maxWidth)
            val height = if (scrollable) total else total.coerceAtMost(if (viewport == Constraints.Infinity) total else viewport)
            layout(width, height.coerceAtLeast(0)) {
                var y = 0
                measurables.indices.forEach { i ->
                    val p = if (i == flexIndex) flex!! else fixed.getValue(i)
                    p.placeRelative(0, y); y += p.height + gap
                }
            }
        }
    }
}
