package app.arislushai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import kotlinx.coroutines.launch

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.TextHi, unfocusedTextColor = Palette.TextHi,
    focusedBorderColor = Palette.Cyan.copy(alpha = 0.85f), unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
    focusedLabelColor = Palette.Cyan, unfocusedLabelColor = Palette.TextLo, cursorColor = Palette.Cyan,
)

@Composable
fun AiScreen(cfg: LlmConfig, onChange: (LlmConfig) -> Unit, onProvider: (Provider) -> Unit) {
    var keyInput by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCard(tint = Palette.Pink) {
            SectionLabel("AI summaries (optional)")
            Text(
                "Adds a ✦ Summary button while recording and an automatic summary + title when a recording ends. " +
                    "Everything else keeps working offline without it.",
                color = Palette.TextHi.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp,
            )
            Text(
                "Privacy: only the transcript TEXT is sent, never audio, and only to the provider below, only when you press " +
                    "✦ Summary or when a recording ends with auto-summary on. Without a key nothing is ever sent. Your key is " +
                    "encrypted on this phone.",
                color = Palette.TextLo, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }

        GlassCard {
            SectionLabel("Provider")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Provider.entries.forEach { p ->
                    GlassChip(p.label, cfg.provider == p, { if (cfg.provider != p) { onProvider(p); keyInput = ""; testResult = null } })
                }
            }
            SectionLabel("API key")
            if (cfg.apiKey.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("✓ Saved  ····${cfg.apiKey.takeLast(4)}", color = Palette.Good, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { onChange(cfg.copy(apiKey = "")); testResult = null }) { Text("Remove", color = Palette.Rec) }
                }
            } else {
                OutlinedTextField(
                    value = keyInput, onValueChange = { keyInput = it },
                    label = { Text(if (cfg.provider == Provider.GEMINI) "Gemini API key (AI Studio)" else "OpenRouter API key (sk-or-…)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                )
                GlassButton("Save key", { onChange(cfg.copy(apiKey = keyInput.trim())); keyInput = "" }, Palette.Violet, Modifier.fillMaxWidth(), enabled = keyInput.isNotBlank())
            }
        }

        GlassCard {
            SectionLabel("Model")
            OutlinedTextField(
                value = cfg.model, onValueChange = { onChange(cfg.copy(model = it.trim())) },
                label = { Text(if (cfg.provider == Provider.GEMINI) "Gemini model id" else "OpenRouter model id (vendor/model)") },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            )
            GlassButton("Browse available models", { browse = true }, Palette.Cyan, Modifier.fillMaxWidth())
            Text(
                if (cfg.provider == Provider.OPENROUTER) "OpenRouter gives one key access to hundreds of models; the list is fetched live."
                else "Gemini lists the models your key can use. Defaults can go out of date, so check with Test connection.",
                color = Palette.TextLo, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }

        GlassCard {
            SectionLabel("Summary language")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("English", "Indonesian", "Russian").forEach { l -> GlassChip(l, cfg.language == l, { onChange(cfg.copy(language = l)) }) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Summarize when a recording ends", color = Palette.TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Also writes the History title. Off = only the ✦ button sends text.", color = Palette.TextLo, fontSize = 12.sp)
                }
                Switch(
                    checked = cfg.autoSummary, onCheckedChange = { onChange(cfg.copy(autoSummary = it)) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Palette.Cyan.copy(alpha = 0.7f), checkedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.14f), uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                        uncheckedBorderColor = Color.Transparent, checkedBorderColor = Color.Transparent,
                    ),
                )
            }
        }

        GlassCard {
            GlassButton(
                if (testing) "Testing…" else "Test connection",
                {
                    testing = true; testResult = null
                    scope.launch {
                        testResult = try { true to "Connected. Model replied: ${LlmClient.test(cfg)}" }
                        catch (e: Exception) { false to (e.message ?: e.javaClass.simpleName) }
                        testing = false
                    }
                },
                Palette.Good, Modifier.fillMaxWidth(), enabled = cfg.ready && !testing,
            )
            testResult?.let { (ok, msg) -> Text(msg, color = if (ok) Palette.Good else Palette.Rec, fontSize = 13.sp, lineHeight = 18.sp) }
            if (!cfg.ready) Text("Save a key and a model first.", color = Palette.TextLo, fontSize = 12.sp)
        }
    }

    if (browse) ModelPicker(cfg, onPick = { onChange(cfg.copy(model = it)); browse = false }, onDismiss = { browse = false })
}

@Composable
private fun ModelPicker(cfg: LlmConfig, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var models by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(cfg.provider, cfg.apiKey) {
        models = null; error = null
        try { models = LlmClient.listModels(cfg) } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.BgMid,
        title = { Text("${cfg.provider.label} models", color = Palette.TextHi) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                )
                when {
                    error != null -> Text(error!!, color = Palette.Rec, fontSize = 13.sp)
                    models == null -> Text("Loading…", color = Palette.TextLo)
                    else -> {
                        val shown = models!!.filter { it.contains(query.trim(), ignoreCase = true) }
                        Text("${shown.size} of ${models!!.size}", color = Palette.TextLo, fontSize = 12.sp)
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(shown.take(300)) { id ->
                                Text(
                                    id, color = if (id == cfg.model) Palette.Cyan else Palette.TextHi, fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Palette.TextHi) } },
    )
}
