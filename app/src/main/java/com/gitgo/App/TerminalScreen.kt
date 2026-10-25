package com.gitgo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private val TermBg = Color(0xFF0B0F0B)
private val TermFg = Color(0xFFD6F5D6)
private val TermPrompt = Color(0xFF7CFC7C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(startDir: File?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = (ctx.applicationContext as GitGoApp).prefs
    val term = remember { GitTerminal(GitOps(ctx.applicationContext, prefs), prefs, startDir) }
    val lines = remember { mutableStateListOf(tr(R.string.term_greeting)) }
    val history = remember { mutableListOf<String>() }
    var histPos by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf(term.prompt()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    fun exec(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty() || busy) return
        input = ""
        lines += "$promptText $c"
        history += c
        histPos = history.size
        busy = true
        scope.launch {
            val out = term.run(c) { l -> scope.launch(Dispatchers.Main) { lines += l } }
            if (out == CLEAR) lines.clear() else if (out.isNotEmpty()) lines += out.trimEnd()
            promptText = term.prompt()
            busy = false
        }
    }

    Scaffold(
        containerColor = TermBg,
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.close)) } }
            )
        },
        modifier = Modifier.imePadding()
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(TermBg)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), state = listState) {
                items(lines.size) { i ->
                    val l = lines[i]
                    Text(
                        l, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        color = if (l.startsWith("gitgo:")) TermPrompt else TermFg
                    )
                }
            }
            LazyRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("git status", "git add .", "git pull", "git push", "git log", "ls", "clear")) { c ->
                    AssistChip(onClick = { exec(c) }, label = { Text(c, fontFamily = FontFamily.Monospace, fontSize = 12.sp) })
                }
            }
            Row(Modifier.padding(8.dp)) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = TermFg),
                    placeholder = { Text("$promptText ", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSend = { exec(input) })
                )
                IconButton(onClick = {
                    if (history.isNotEmpty()) { histPos = (histPos - 1).coerceAtLeast(0); input = history[histPos] }
                }) { Text("↑", color = TermFg) }
            }
        }
    }
}
