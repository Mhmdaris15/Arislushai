package app.arislushai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Process-wide log shared by the activity and the live service. */
object AppBus {
    val lines = MutableStateFlow<List<String>>(emptyList())
    val busy = MutableStateFlow(false)
    val live = MutableStateFlow(LiveState())

    @Synchronized
    fun log(msg: String) {
        android.util.Log.i("Arislushai", msg)
        lines.value = (lines.value + msg).takeLast(300)
    }
}

/** One finalized caption: Russian text plus whatever translations were produced for it. */
data class CaptionItem(
    val index: Int,
    /** Chunk start in recording time (seconds). Chunk time, not word time. */
    val startSec: Double,
    val ru: String,
    val translations: Map<String, String>,
)

data class LiveState(
    /** All finalized captions of the current/last recording; unbounded (a 3 h lecture is only a few thousand items). */
    val captions: List<CaptionItem> = emptyList(),
    val startedAtMs: Long = 0L,
    /** Folder name under runs/ for this recording; used to save its summary next to its captions. */
    val sessionDir: String = "",
    val running: Boolean = false,
    val status: String = "idle",
    val segments: Int = 0,
    val lastRu: String = "",
    /** Tentative text of the speech still in progress. */
    val partialRu: String = "",
    val partialTranslations: Map<String, String> = emptyMap(),
    val lastTranslations: Map<String, String> = emptyMap(),
    val lagSec: Double = 0.0,
    val queueDepth: Int = 0,
    val elapsedSec: Double = 0.0,
)

data class DeviceSample(
    val elapsedSec: Double,
    val batteryPct: Int,
    val batteryTempC: Double,
    val thermalStatus: Int,
    val pssMb: Double,
    val nativeHeapMb: Double,
)

class DeviceSampler(private val ctx: Context, dir: File) {
    private val csv = File(dir, "samples.csv").also {
        it.writeText("elapsed_s,battery_pct,battery_temp_c,thermal_status,pss_mb,native_heap_mb\n")
    }
    private val t0 = System.nanoTime()
    private var job: Job? = null
    val samples = mutableListOf<DeviceSample>()

    fun snapshot(): DeviceSample {
        val bi: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        } else -1
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return DeviceSample(
            elapsedSec = (System.nanoTime() - t0) / 1e9,
            batteryPct = if (level >= 0) level * 100 / scale else -1,
            batteryTempC = temp,
            thermalStatus = thermal,
            pssMb = mi.totalPss / 1024.0,
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1048576.0,
        )
    }

    private fun record(s: DeviceSample) {
        samples += s
        csv.appendText("%.0f,%d,%.1f,%d,%.0f,%.0f\n".format(Locale.US, s.elapsedSec, s.batteryPct, s.batteryTempC, s.thermalStatus, s.pssMb, s.nativeHeapMb))
    }

    fun start(scope: CoroutineScope, everyMs: Long = 30_000) {
        record(snapshot())
        job = scope.launch {
            while (isActive) {
                delay(everyMs)
                record(snapshot())
            }
        }
    }

    fun stop() {
        job?.cancel()
        record(snapshot())
    }
}

fun newRunDir(ctx: Context, label: String): File {
    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return File(ctx.getExternalFilesDir(null), "runs/$ts-$label").also { it.mkdirs() }
}

private fun pct(values: List<Double>, p: Double): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.sorted()
    return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
}

class RunLogger(private val dir: File) {
    private val jsonl = File(dir, "results.jsonl").bufferedWriter()
    val results = mutableListOf<SegmentResult>()

    @Synchronized
    fun add(r: SegmentResult) {
        results += r
        val o = JSONObject()
            .put("index", r.index).put("start_s", r.startSec).put("dur_s", r.durSec)
            .put("ru", r.ru).put("decode_ms", r.decodeMs)
            .put("queue_wait_ms", r.queueWaitMs).put("post_segment_ms", r.postSegmentMs)
            .put("lag_s", r.lagSec).put("queue_depth", r.queueDepth)
            .put("first_word_delay_s", r.lagSec + r.durSec)
        r.translations.forEach { (k, v) -> o.put("tr_$k", v).put("tr_${k}_ms", r.translateMs[k]) }
        jsonl.appendLine(o.toString())
        jsonl.flush()
    }

    /** Writes report.txt and returns its text. Only reports measured numbers. */
    @Synchronized
    fun finish(
        header: Map<String, String>,
        audioSec: Double,
        wallSec: Double,
        modelLoadMs: Double,
        maxQueueDepth: Int,
        sampler: DeviceSampler,
    ): String {
        jsonl.close()
        val decode = results.map { it.decodeMs }
        val post = results.map { it.postSegmentMs }
        val lag = results.map { it.lagSec }
        val decodeAudio = results.sumOf { it.durSec }
        val s = sampler.samples
        val sb = StringBuilder()
        sb.appendLine("== Арислушай run report ==")
        header.forEach { (k, v) -> sb.appendLine("$k: $v") }
        sb.appendLine("segments: ${results.size}  audio_s: %.1f  wall_s: %.1f".format(Locale.US, audioSec, wallSec))
        sb.appendLine("model_load_ms: %.0f".format(Locale.US, modelLoadMs))
        sb.appendLine("RTF (wall/audio): %.3f".format(Locale.US, wallSec / audioSec))
        sb.appendLine("RTF (decode-only, speech audio): %.3f".format(Locale.US, decode.sum() / 1000.0 / decodeAudio))
        sb.appendLine("decode_ms p50/p95/max: %.0f / %.0f / %.0f".format(Locale.US, pct(decode, .5), pct(decode, .95), decode.maxOrNull() ?: Double.NaN))
        sb.appendLine("post_segment_ms p50/p95/max: %.0f / %.0f / %.0f".format(Locale.US, pct(post, .5), pct(post, .95), post.maxOrNull() ?: Double.NaN))
        sb.appendLine("lag_s p50/p95/max (capture clock - segment end): %.1f / %.1f / %.1f".format(Locale.US, pct(lag, .5), pct(lag, .95), lag.maxOrNull() ?: Double.NaN))
        val firstWord = results.map { it.lagSec + it.durSec }
        sb.appendLine("first_word_delay_s p50/p95/max (chunk length + lag; what a viewer actually waits for the chunk's first word): %.1f / %.1f / %.1f".format(Locale.US, pct(firstWord, .5), pct(firstWord, .95), firstWord.maxOrNull() ?: Double.NaN))
        sb.appendLine("chunk_dur_s p50/max: %.1f / %.1f".format(Locale.US, pct(results.map { it.durSec }, .5), results.maxOfOrNull { it.durSec } ?: Double.NaN))
        sb.appendLine("max_queue_depth: $maxQueueDepth")
        if (s.size >= 2) {
            val a = s.first(); val b = s.last()
            sb.appendLine("battery_pct: ${a.batteryPct} -> ${b.batteryPct}  battery_temp_c: %.1f -> %.1f (max %.1f)".format(Locale.US, a.batteryTempC, b.batteryTempC, s.maxOf { it.batteryTempC }))
            sb.appendLine("thermal_status max: ${s.maxOf { it.thermalStatus }} (0=none .. 6=shutdown)")
            sb.appendLine("pss_mb first/max/last: %.0f / %.0f / %.0f  native_heap_mb max: %.0f".format(Locale.US, a.pssMb, s.maxOf { it.pssMb }, b.pssMb, s.maxOf { it.nativeHeapMb }))
        }
        sb.appendLine("NOTE: accuracy and translation adequacy are NOT measured here; check results.jsonl by hand.")
        File(dir, "report.txt").writeText(sb.toString())
        return sb.toString()
    }
}
