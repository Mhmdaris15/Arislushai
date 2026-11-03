package app.arislushai

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val filesDir = getExternalFilesDir(null)!!

        // Headless trigger for repeatable runs:
        // adb shell am start -n app.arislushai/.MainActivity --es mode file --es model giga-v3-transducer \
        //     --es targets en,id --ez paced false --es wav input.wav
        if (intent?.getStringExtra("mode") == "file") {
            val spec = Models.byId(intent.getStringExtra("model") ?: "") ?: Models.all.first()
            val targets = intent.getStringExtra("targets")?.split(",")?.filter { it.isNotBlank() }.orEmpty()
            BenchService.start(
                applicationContext, spec.id, targets,
                intent.getBooleanExtra("paced", false), intent.getStringExtra("wav") ?: "input.wav",
            )
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Cyan, background = Palette.BgTop)) {
                GlassBackground { App(filesDir) }
            }
        }
    }

    @Composable
    private fun App(filesDir: File) {
        var tab by remember { mutableIntStateOf(0) }
        var model by remember { mutableStateOf(Models.all.first()) }
        var en by remember { mutableStateOf(true) }
        var id by remember { mutableStateOf(true) }
        var paced by remember { mutableStateOf(false) }
        var wavName by remember { mutableStateOf("input.wav") }
        val busy by AppBus.busy.collectAsState()
        val live by AppBus.live.collectAsState()
        val lines by AppBus.lines.collectAsState()
        val targets = listOfNotNull("en".takeIf { en }, "id".takeIf { id })
        val summary by SummaryHub.state.collectAsState()
        var llm by remember { mutableStateOf(LlmPrefs.load(applicationContext)) }
        val updateLlm: (LlmConfig) -> Unit = { llm = it; LlmPrefs.save(applicationContext, it) }

        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                LiveService.start(applicationContext, model.id, targets)
            } else AppBus.log("RECORD_AUDIO denied")
        }

        val setup: @Composable () -> Unit = {
            SetupCard(filesDir, model, { model = it }, en, { en = it }, id, { id = it })
        }
        val statusText: String
        val statusColor: androidx.compose.ui.graphics.Color
        val pulsing: Boolean
        when {
            live.running -> { statusText = "Recording"; statusColor = Palette.Rec; pulsing = true }
            busy -> { statusText = "Processing file"; statusColor = Palette.Warn; pulsing = true }
            else -> { statusText = "Ready"; statusColor = Palette.Good; pulsing = false }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            AppHeader(statusText, statusColor, pulsing)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> LiveScreen(
                        live = live, setup = setup, busy = busy,
                        onStart = { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
                        onStop = { LiveService.stop(applicationContext) },
                        summary = summary, llmReady = llm.ready,
                        // Manual trigger: the only way live text leaves the phone besides auto-summary at the end.
                        onSummarize = { SummaryHub.scope.launch { Summarizer.run(applicationContext, llm, final = false) } },
                    )
                    1 -> HistoryScreen(
                        filesDir, refreshKey = Triple(live.running, busy, summary.updatedAtMs), llmReady = llm.ready,
                        onGenerate = { dir -> withContext(Dispatchers.IO) { Summarizer.runSaved(applicationContext, dir, llm) } },
                    )
                    2 -> AiScreen(
                        cfg = llm, onChange = updateLlm,
                        onProvider = { p -> updateLlm(LlmPrefs.load(applicationContext, p).copy(language = llm.language, autoSummary = llm.autoSummary)) },
                    )
                    else -> LabScreen(
                        setup = setup,
                        paced = paced, onPaced = { paced = it },
                        wavName = wavName, onWavName = { wavName = it },
                        busy = busy, liveRunning = live.running,
                        onRunFile = { BenchService.start(applicationContext, model.id, targets, paced, wavName) },
                        lines = lines,
                    )
                }
            }
            GlassTabBar(listOf("Live", "History", "AI", "Lab"), tab) { tab = it }
        }
    }
}
