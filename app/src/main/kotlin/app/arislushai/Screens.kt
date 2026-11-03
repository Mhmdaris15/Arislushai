package app.arislushai

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun mmss(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

private val dateTitle = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())

// ───────────────────────── caption building blocks ─────────────────────────

@Composable
fun CaptionBubble(item: CaptionItem, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(mmss(item.startSec), color = Palette.TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(item.ru, color = Palette.TextHi, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
        item.translations.forEach { (lang, text) ->
            if (text.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Text(lang.uppercase(), color = Palette.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                Text(text, color = Palette.TextHi.copy(alpha = 0.82f), fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
    }
}

/** Speech still in progress: dimmer and italic so nobody mistakes it for a finished caption. */
@Composable
private fun TentativeBubble(ru: String, translations: Map<String, String>) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Cyan.copy(alpha = 0.07f))
            .border(1.dp, Palette.Cyan.copy(alpha = 0.3f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("LISTENING…", color = Palette.Cyan.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        Text(ru, color = Palette.TextHi.copy(alpha = 0.78f), fontSize = 18.sp, lineHeight = 25.sp, fontStyle = FontStyle.Italic)
        translations.forEach { (lang, text) ->
            if (text.isNotBlank()) Text("${lang.uppercase()}  $text", color = Palette.Cyan.copy(alpha = 0.65f), fontSize = 14.sp, lineHeight = 20.sp, fontStyle = FontStyle.Italic)
        }
    }
}

@Composable
fun AppHeader(statusText: String, statusColor: Color, pulsing: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Арислушай", color = Palette.TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("RU → EN / ID · offline", color = Palette.TextLo, fontSize = 13.sp)
        }
        StatusBadge(statusText, statusColor, pulsing)
    }
}

@Composable
fun GlassTabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (on) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Palette.Violet.copy(alpha = 0.8f), Palette.Cyan.copy(alpha = 0.55f)))
                        else androidx.compose.ui.graphics.SolidColor(Color.Transparent)
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.White else Palette.TextLo, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp)
            }
        }
    }
}

// ───────────────────────── Live tab ─────────────────────────

@Composable
fun LiveScreen(
    live: LiveState,
    setup: @Composable () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    busy: Boolean,
    summary: SummaryUi,
    llmReady: Boolean,
    onSummarize: () -> Unit,
) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }

    // Follow the newest caption unless the reader scrolled away; resume once they reach the end again.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect { follow = !listState.canScrollForward }
    }
    LaunchedEffect(live.captions.size, live.partialRu, follow) {
        if (follow) listState.scrollBy(1_000_000f)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (live.running) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("elapsed", mmss(live.elapsedSec), Modifier.weight(1f))
                StatPill("captions", live.captions.size.toString(), Modifier.weight(1f))
                StatPill("queue", live.queueDepth.toString(), Modifier.weight(1f))
                StatPill("lag", "%.1fs".format(Locale.US, live.lagSec), Modifier.weight(1f))
            }
        } else {
            setup()
        }

        if (summary.running || summary.text.isNotBlank() || summary.error.isNotBlank()) SummaryCard(summary)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (live.captions.isEmpty() && live.partialRu.isBlank() && !live.running) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No recording yet", color = Palette.TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tap Start recording. Captions appear here as an endless feed you can scroll back through while it keeps recording.",
                        color = Palette.TextLo, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "header") {
                        val started = if (live.startedAtMs > 0) dateTitle.format(Date(live.startedAtMs)) else ""
                        Text(
                            "RECORDING · $started" + if (live.running) "" else " · finished",
                            color = Palette.TextLo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(live.captions, key = { it.index }) { CaptionBubble(it) }
                    if (live.partialRu.isNotBlank()) item(key = "tentative") { TentativeBubble(live.partialRu, live.partialTranslations) }
                    if (live.running && live.partialRu.isBlank() && live.captions.isEmpty()) item(key = "wait") {
                        Text("Listening… ${live.status}", color = Palette.TextLo, fontSize = 14.sp)
                    }
                }
            }
            if (!follow) {
                GlassButton("↓ Live", { follow = true }, Palette.Violet, Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Manual only: nothing is sent to an LLM until this is pressed (or the recording ends with auto-summary on).
            if (llmReady && live.captions.isNotEmpty()) {
                GlassButton(
                    if (summary.running) "Summarizing…" else "✦ Summary", onSummarize, Palette.Pink,
                    Modifier.weight(0.8f), enabled = !summary.running,
                )
            }
            if (!live.running) {
                GlassButton("Start recording", onStart, Palette.Cyan, Modifier.weight(1.2f), enabled = !busy)
            } else {
                GlassButton("Stop recording", onStop, Palette.Rec, Modifier.weight(1.2f))
            }
        }
    }
}

/** Pinned, collapsible summary of the current recording. Collapsed it shows the title and the first line. */
@Composable
fun SummaryCard(s: SummaryUi, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    GlassCard(modifier.clickable { open = !open }, tint = Palette.Pink) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionLabel(
                    if (s.running) "Summarizing…"
                    else "Summary" + if (s.updatedAtMs > 0) " · updated " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.updatedAtMs)) else ""
                )
                if (s.title.isNotBlank()) Text(s.title, color = Palette.TextHi, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            if (s.text.isNotBlank()) Text(if (open) "▾" else "▸", color = Palette.TextHi, fontSize = 18.sp)
        }
        if (s.error.isNotBlank()) Text(s.error, color = Palette.Rec, fontSize = 13.sp, lineHeight = 18.sp)
        if (s.text.isNotBlank()) {
            if (open) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text(s.text, color = Palette.TextHi.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 20.sp)
                }
            } else {
                Text(s.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), color = Palette.TextLo, fontSize = 13.sp, maxLines = 2)
            }
        }
    }
}

// ───────────────────────── History tab ─────────────────────────

@Composable
fun HistoryScreen(filesDir: File, refreshKey: Any, llmReady: Boolean, onGenerate: suspend (String) -> String?) {
    var sessions by remember { mutableStateOf<List<SavedSession>>(emptyList()) }
    val open = remember { mutableStateMapOf<String, List<CaptionItem>>() }
    var pendingDelete by remember { mutableStateOf<SavedSession?>(null) }
    val generating = remember { mutableStateMapOf<String, Boolean>() }
    val genErrors = remember { mutableStateMapOf<String, String>() }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey, reload) {
        sessions = withContext(Dispatchers.IO) { HistoryRepo.list(filesDir) }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (sessions.isEmpty()) item {
            Text("Saved recordings will appear here, one section per recording.", color = Palette.TextLo, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
        sessions.forEach { s ->
            item(key = "h-${s.dirName}") {
                SessionHeader(
                    s, expanded = open.containsKey(s.dirName),
                    onToggle = {
                        if (open.containsKey(s.dirName)) open.remove(s.dirName)
                        else scope.launch { open[s.dirName] = withContext(Dispatchers.IO) { HistoryRepo.load(filesDir, s.dirName) } }
                    },
                    onDelete = { pendingDelete = s },
                    llmReady = llmReady,
                    generating = generating[s.dirName] == true,
                    error = genErrors[s.dirName],
                    onGenerate = {
                        generating[s.dirName] = true
                        genErrors.remove(s.dirName)
                        scope.launch {
                            val err = onGenerate(s.dirName)
                            generating.remove(s.dirName)
                            if (err != null) genErrors[s.dirName] = err else reload++
                        }
                    },
                )
            }
            val captions = open[s.dirName]
            if (captions != null) items(captions, key = { "${s.dirName}#${it.index}" }) { CaptionBubble(it, Modifier.padding(start = 10.dp)) }
        }
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Palette.BgMid,
            title = { Text("Delete recording?", color = Palette.TextHi) },
            text = { Text("Removes the audio and the transcript of ${dateTitle.format(Date(s.startedAtMs))} from this phone.", color = Palette.TextLo) },
            confirmButton = {
                TextButton(onClick = {
                    HistoryRepo.delete(filesDir, s.dirName)
                    open.remove(s.dirName)
                    sessions = sessions - s
                    pendingDelete = null
                }) { Text("Delete", color = Palette.Rec) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel", color = Palette.TextHi) } },
        )
    }
}

@Composable
private fun SessionHeader(
    s: SavedSession, expanded: Boolean, onToggle: () -> Unit, onDelete: () -> Unit,
    llmReady: Boolean, generating: Boolean, error: String?, onGenerate: () -> Unit,
) {
    val date = dateTitle.format(Date(s.startedAtMs))
    GlassCard(Modifier.clickable(onClick = onToggle), tint = Palette.Violet) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                // A meaningful (LLM-made) title when there is one; otherwise the date.
                Text(s.title ?: date, color = Palette.TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
                Text(
                    (if (s.title != null) "$date · " else "") + "${mmss(s.durationSec)} · ${s.captionCount} captions · ${s.kind}",
                    color = Palette.TextLo, fontSize = 12.sp,
                )
            }
            Text(if (expanded) "▾" else "▸", color = Palette.TextHi, fontSize = 20.sp)
        }
        if (!expanded) Text(s.summary?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: s.preview, color = Palette.TextHi.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 2, lineHeight = 19.sp)
        if (expanded) {
            if (s.summary != null) {
                SectionLabel("Summary")
                Text(s.summary, color = Palette.TextHi.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 20.sp)
            } else if (llmReady) {
                GlassButton(if (generating) "Summarizing…" else "✦ Generate summary & title", onGenerate, Palette.Pink, Modifier.fillMaxWidth(), enabled = !generating)
            } else {
                Text("No summary yet. Add an API key in the AI tab to generate one.", color = Palette.TextLo, fontSize = 13.sp)
            }
            if (error != null) Text(error, color = Palette.Rec, fontSize = 13.sp, lineHeight = 18.sp)
            TextButton(onClick = onDelete) { Text("Delete recording", color = Palette.Rec) }
        }
    }
}

// ───────────────────────── Lab tab (benchmark tools) ─────────────────────────

@Composable
fun LabScreen(
    setup: @Composable () -> Unit,
    paced: Boolean, onPaced: (Boolean) -> Unit,
    wavName: String, onWavName: (String) -> Unit,
    busy: Boolean, liveRunning: Boolean, onRunFile: () -> Unit,
    lines: List<String>,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        setup()
        GlassCard {
            SectionLabel("File benchmark")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Real-time pacing", color = Palette.TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Feeds the file at live speed to expose backlog and delay", color = Palette.TextLo, fontSize = 12.sp)
                }
                Switch(
                    checked = paced, onCheckedChange = onPaced,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Palette.Cyan.copy(alpha = 0.7f), checkedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.14f), uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                        uncheckedBorderColor = Color.Transparent, checkedBorderColor = Color.Transparent,
                    ),
                )
            }
            OutlinedTextField(
                value = wavName, onValueChange = onWavName,
                label = { Text("16 kHz mono PCM16 WAV in app files dir") },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Palette.TextHi, unfocusedTextColor = Palette.TextHi,
                    focusedBorderColor = Palette.Cyan.copy(alpha = 0.85f), unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                    focusedLabelColor = Palette.Cyan, unfocusedLabelColor = Palette.TextLo, cursorColor = Palette.Cyan,
                ),
            )
            GlassButton(if (busy) "Running…" else "Run file", onRunFile, Palette.Violet, Modifier.fillMaxWidth(), enabled = !busy && !liveRunning)
        }
        GlassCard {
            SectionLabel("Run log")
            Text(
                lines.takeLast(40).reversed().joinToString("\n").ifBlank { "Nothing yet." },
                color = Palette.TextLo, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ───────────────────────── shared setup card ─────────────────────────

@Composable
fun SetupCard(
    filesDir: File,
    model: ModelSpec, onModel: (ModelSpec) -> Unit,
    en: Boolean, onEn: (Boolean) -> Unit,
    id: Boolean, onId: (Boolean) -> Unit,
) {
    GlassCard {
        SectionLabel("Speech model")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Models.all.forEach { m ->
                GlassChip(m.id, selected = model == m, onClick = { onModel(m) }, dot = if (Models.isInstalled(filesDir, m)) Palette.Good else Palette.Rec)
            }
        }
        SectionLabel("Translate to")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("English", en, { onEn(!en) })
            GlassChip("Indonesian", id, { onId(!id) })
        }
    }
}
