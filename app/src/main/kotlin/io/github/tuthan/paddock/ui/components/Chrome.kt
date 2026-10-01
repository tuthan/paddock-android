package io.github.tuthan.paddock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** A 48 dp icon button with a rounded pressed shape. [description] is what TalkBack reads; it is required. */
@Composable
fun PaddockIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    Box(
        modifier.size(PaddockTokens.spacing.touchTarget).clip(RoundedCornerShape(PaddockTokens.radii.iconButton))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp)) }
}

/**
 * A screen's top bar: an optional back button, the title (a heading) and its optional second line, then [actions].
 * [compact] is the agent screen's form: a 17 sp title over two lines instead of the 22 sp screen name.
 */
@Composable
fun ScreenHeader(
    title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, subtitle: String? = null, compact: Boolean = false,
    backDescription: String = "Back", actions: @Composable RowScope.() -> Unit = {},
) {
    val c = PaddockTokens.colors
    Row(
        modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else PaddockTokens.spacing.gutter, end = 4.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = if (subtitle != null) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onBack != null) PaddockIconButton(PaddockIcons.Back, backDescription, onBack)
        Column(
            Modifier.weight(1f).heightIn(min = PaddockTokens.spacing.touchTarget).padding(top = if (subtitle != null) 4.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Text(
                title, style = if (compact) PaddockTokens.type.headerTitle else PaddockTokens.type.screenTitle, color = c.title,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) Text(subtitle, style = PaddockTokens.type.secondary, color = c.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** A destination in the bottom bar. */
data class NavItem(val label: String, val icon: ImageVector)

/** The bottom bar: icon over word, the current one in the title colour, the rest dim. Announced as tabs. */
@Composable
fun PaddockNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val line = c.line()
    Row(
        modifier.fillMaxWidth().background(c.surface)
            .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 8.dp, vertical = 6.dp).selectableGroup(),
    ) {
        items.forEachIndexed { i, item ->
            val on = i == selected
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Icon(item.icon, contentDescription = null, tint = if (on) c.title else c.dim, modifier = Modifier.size(22.dp))
                Text(item.label, style = PaddockTokens.type.nav, color = if (on) c.title else c.dim)
            }
        }
    }
}
