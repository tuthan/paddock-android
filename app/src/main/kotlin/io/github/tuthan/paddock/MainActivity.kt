package io.github.tuthan.paddock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.tuthan.paddock.ui.theme.PaddockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge on every API level, so the IME inset reaches `imePadding` below API 30 too (Add machine keeps Connect visible).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as PaddockApp).graph
        setContent { PaddockTheme { PaddockRoot(graph) } }
    }
}
