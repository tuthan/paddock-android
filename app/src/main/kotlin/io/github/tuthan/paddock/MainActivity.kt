package io.github.tuthan.paddock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import io.github.tuthan.paddock.ui.theme.PaddockTokens

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PaddockTheme { EmptyScreen() } }
    }
}

/** Phase 01 shows nothing but the themed ground and the app name; screens start in Phase 04. */
@Composable
private fun EmptyScreen() {
    Box(
        Modifier
            .fillMaxSize()
            // Target 37 enforces edge-to-edge: keep content clear of the system bars and cutouts.
            .safeDrawingPadding()
            .padding(PaddockTokens.spacing.gutter),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = PaddockTokens.type.screenTitle,
            color = PaddockTokens.colors.title,
        )
    }
}
