package com.gitgo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import java.io.File

class TerminalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra("dir")?.let { File(it) }
        setContent {
            val prefs = (application as GitGoApp).prefs
            val kind = remember { runCatching { ThemeKind.valueOf(prefs.themeName) }.getOrDefault(ThemeKind.DARK) }
            GitGoTheme(kind) { TerminalScreen(start) { finish() } }
        }
    }
}
