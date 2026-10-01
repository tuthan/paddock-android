package io.github.tuthan.paddock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.tuthan.paddock.ui.theme.PaddockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as PaddockApp).graph
        setContent { PaddockTheme { PaddockRoot(graph) } }
    }
}
