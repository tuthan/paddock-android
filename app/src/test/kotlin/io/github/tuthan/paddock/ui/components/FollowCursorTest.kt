package io.github.tuthan.paddock.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert
import org.junit.Test

/** A terminal larger than the view, with the cursor where the person types (Terminal tab, keyboard up). */
class FollowCursorTest {
    private val cell = CellMetrics(cellWidth = 6f, cellHeight = 12f, baseline = 9f)
    private val cols = 208; private val rows = 79
    private val view = IntSize(1_248, 240) // 208 columns wide, 20 rows tall

    private fun follow(current: Offset, row: Int, col: Int = 0) = followCursorOffset(current, row, col, cols, rows, cell, view)
    private fun same(expected: Offset, actual: Offset) {
        Assert.assertEquals("x", expected.x, actual.x, 0.001f); Assert.assertEquals("y", expected.y, actual.y, 0.001f)
    }
    private fun near(message: String, expected: Float, actual: Float) = Assert.assertEquals(message, expected, actual, 0.001f)

    @Test fun aCursorAtTheBottomRowBringsTheBottomIntoView() {
        val o = follow(Offset.Zero, row = rows - 1)
        near("the last row ends at the bottom of the view", 240f - rows * 12f, o.y)
        near("no sideways move", 0f, o.x)
    }

    @Test fun aCursorAlreadyInViewMovesNothing() {
        val held = Offset(0f, -120f) // rows 10 to 29 are showing
        same(held, follow(held, row = 15))
        same(held, follow(held, row = 10))
        same(held, follow(held, row = 29))
    }

    @Test fun aCursorJustOutsideMovesTheGridByOneRowOnly() {
        val held = Offset(0f, -120f)
        same(Offset(0f, -132f), follow(held, row = 30)) // one row below: the grid moves up by one row
        same(Offset(0f, -108f), follow(held, row = 9)) // one row above: the grid moves down by one row
    }

    @Test fun neverPastTheGridsEdges() {
        same(Offset.Zero, follow(Offset(0f, -500f), row = 0))
        near("a cursor row past the grid ends at the bottom edge", 240f - rows * 12f, follow(Offset.Zero, row = 500).y)
    }

    @Test fun aZoomedInGridFollowsTheColumnToo() {
        val big = CellMetrics(cellWidth = 24f, cellHeight = 48f, baseline = 36f) // 52 columns and 5 rows fit
        val o = followCursorOffset(Offset.Zero, row = 0, col = 100, cols = cols, rows = rows, cell = big, view = view)
        near("column 100 ends at the right edge", 1_248f - 101 * 24f, o.x)
        near("row 0 stays", 0f, o.y)
    }

    @Test fun aGridSmallerThanTheViewNeverMoves() {
        same(Offset.Zero, followCursorOffset(Offset.Zero, row = 5, col = 5, cols = 80, rows = 10, cell = cell, view = view))
    }
}
