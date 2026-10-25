package com.gitgo.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import java.io.File

@Composable
fun synColors(): SynColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) SynColors(Color(0xFFFFA45C), Color(0xFF8BC77A), Color(0xFF8A8F98), Color(0xFF7AB8F5))
    else SynColors(Color(0xFF9A4E00), Color(0xFF2E7D32), Color(0xFF6B7280), Color(0xFF1565C0))
}

private sealed interface DiffState {
    data object Loading : DiffState
    data class Note(val text: String) : DiffState
    data class Lines(val lines: List<DLine>) : DiffState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(dir: File, path: String, onEdit: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = File(dir, path)
    val ext = file.extension
    val col = synColors()
    var tick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DiffState>(DiffState.Loading) }
    var editing by remember { mutableStateOf<DLine?>(null) }
    var editText by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        state = withContext(Dispatchers.IO) {
            try {
                // ссылка из репозитория не должна выводить нас за его пределы
                if (file.exists() && !file.isInside(dir)) return@withContext DiffState.Note(tr(R.string.editor_cannot))
                val oldB = Git.open(dir).use { DiffEngine.headBytes(it, path) }
                val newB = if (file.isFile) {
                    if (file.length() > DiffEngine.MAX_BYTES) return@withContext DiffState.Note(tr(R.string.diff_too_big))
                    file.readBytes()
                } else null
                if ((oldB != null && DiffEngine.isBinary(oldB)) || (newB != null && DiffEngine.isBinary(newB)))
                    return@withContext DiffState.Note(tr(R.string.diff_binary))
                val lines = DiffEngine.compute(
                    DiffEngine.lines(oldB?.toString(Charsets.UTF_8) ?: ""),
                    DiffEngine.lines(newB?.toString(Charsets.UTF_8) ?: "")
                )
                if (lines.isEmpty()) DiffState.Note(tr(R.string.diff_no_changes)) else DiffState.Lines(lines)
            } catch (e: Exception) {
                DiffState.Note(tr(R.string.error_fmt, e.message ?: ""))
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(file.name)
                    Text(path, style = MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) } },
            actions = {
                if (file.isFile) IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, tr(R.string.editor_edit)) }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (val st = state) {
                DiffState.Loading -> {}
                is DiffState.Note -> Text(st.text, Modifier.padding(16.dp))
                is DiffState.Lines -> {
                    val adds = st.lines.count { it.kind == DK.ADD }
                    val dels = st.lines.count { it.kind == DK.DEL }
                    Text(
                        "+$adds  −$dels   •   " + tr(R.string.diff_tap_hint),
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                    val minW = LocalConfiguration.current.screenWidthDp.dp
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        LazyColumn(Modifier.widthIn(min = minW)) {
                            items(st.lines) { l ->
                                val bg = when (l.kind) {
                                    DK.ADD -> Color(0x3328A745)
                                    DK.DEL -> Color(0x33D73A49)
                                    DK.SEP -> MaterialTheme.colorScheme.surfaceVariant
                                    DK.CTX -> Color.Transparent
                                }
                                val canEdit = l.newNo > 0 && l.kind != DK.SEP
                                Row(
                                    Modifier.widthIn(min = minW).background(bg)
                                        .then(if (canEdit) Modifier.clickable { editing = l; editText = l.text } else Modifier)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    if (l.kind == DK.SEP) {
                                        Text(
                                            "⋯ " + tr(R.string.diff_hidden, l.text.toInt()),
                                            fontSize = 11.sp, fontStyle = FontStyle.Italic,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        val num = if (l.newNo > 0) l.newNo else l.oldNo
                                        Text(
                                            num.toString().padStart(4) + when (l.kind) { DK.ADD -> " + "; DK.DEL -> " − "; else -> "   " },
                                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            Syntax.highlight(l.text, ext, col),
                                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { l ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(tr(R.string.diff_edit_line, l.newNo)) },
            text = {
                OutlinedTextField(
                    editText, { editText = it }, modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val no = l.newNo
                    val txt = editText
                    editing = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { DiffEngine.editLine(file, no, txt) } }
                        r.exceptionOrNull()?.let { Toast.makeText(ctx, tr(R.string.error_fmt, it.message ?: ""), Toast.LENGTH_LONG).show() }
                        tick++
                    }
                }) { Text(tr(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr(R.string.cancel)) } }
        )
    }
}

private class SyntaxTransformation(val ext: String, val col: SynColors) : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): TransformedText =
        TransformedText(Syntax.highlightAll(text.text, ext, col), OffsetMapping.Identity)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(dir: File, path: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = File(dir, path)
    val col = synColors()
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    val dirty = ok == true && text != original

    LaunchedEffect(path) {
        val t = withContext(Dispatchers.IO) {
            if (!file.isFile || !file.isInside(dir) || file.length() > 500_000) null
            else file.readBytes().takeIf { !DiffEngine.isBinary(it) && DiffEngine.isUtf8(it) }?.toString(Charsets.UTF_8)
        }
        if (t == null) ok = false else { text = t; original = t; ok = true }
    }

    fun close() { if (dirty) askDiscard = true else onClose() }
    BackHandler(onBack = { close() })

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(file.name + if (dirty) " •" else "")
                    Text(path, style = MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) } },
            actions = {
                if (ok == true) IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { file.writeText(text) }
                        original = text
                        Toast.makeText(ctx, tr(R.string.editor_saved), Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.Filled.Save, tr(R.string.save)) }
            }
        )
    }) { pad ->
        when (ok) {
            null -> {}
            false -> Text(tr(R.string.editor_cannot), Modifier.padding(pad).padding(16.dp))
            true -> Box(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = SyntaxTransformation(file.extension, col)
                )
            }
        }
    }

    if (askDiscard) AlertDialog(
        onDismissRequest = { askDiscard = false },
        title = { Text(tr(R.string.editor_discard_title)) },
        confirmButton = { TextButton(onClick = { askDiscard = false; onClose() }) { Text(tr(R.string.editor_discard)) } },
        dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(tr(R.string.cancel)) } }
    )
}
