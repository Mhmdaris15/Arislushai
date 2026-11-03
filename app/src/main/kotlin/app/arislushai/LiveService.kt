package app.arislushai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Microphone foreground service. Captures 16 kHz mono audio, tees it to a WAV on disk
 * (audio preservation does not depend on inference), and runs the same pipeline as the file benchmark.
 * Must be started while the app is visible (Android restricts background starts of microphone services).
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val modelId = intent?.getStringExtra(EXTRA_MODEL) ?: Models.all.first().id
        val targets = intent?.getStringExtra(EXTRA_TARGETS)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val spec = Models.byId(modelId)
        if (spec == null || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppBus.log("Live start refused: unknown model or RECORD_AUDIO not granted")
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        job = scope.launch { capture(spec, targets) }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        enterForeground("Арислушай · recording", "Microphone is capturing audio", ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private suspend fun capture(spec: ModelSpec, targets: List<String>) {
        val filesDir = getExternalFilesDir(null)!!
        val dir = newRunDir(this, "live-${spec.id}")
        val logger = RunLogger(dir)
        val sampler = DeviceSampler(this, dir)
        var translator: MlKitTranslator? = null
        var record: AudioRecord? = null
        var writer: WavWriter? = null
        var pipeline: Pipeline? = null
        val startNs = System.nanoTime()
        val wakeLock = acquireWakeLock("live")
        AppBus.live.value = LiveState(running = true, status = "loading", startedAtMs = System.currentTimeMillis(), sessionDir = dir.name)
        SummaryHub.state.value = SummaryUi()
        try {
            if (targets.isNotEmpty()) {
                translator = MlKitTranslator()
                for (t in targets) translator.ensureModel(t)
            }
            val pipe = Pipeline(filesDir, spec, targets, translator, scope, { r ->
                logger.add(r)
                val prev = AppBus.live.value
                AppBus.live.value = prev.copy(
                    captions = if (r.ru.isBlank()) prev.captions else prev.captions + CaptionItem(r.index, r.startSec, r.ru, r.translations),
                    segments = prev.segments + 1,
                    lastRu = r.ru,
                    partialRu = "",
                    partialTranslations = emptyMap(),
                    lastTranslations = r.translations,
                    lagSec = r.lagSec,
                    queueDepth = r.queueDepth,
                )
                AppBus.log("#${r.index} chunk %.1fs lag %.1fs decode %.0fms: ${r.ru}".format(r.durSec, r.lagSec, r.decodeMs))
            }, onPartial = { text, tr ->
                AppBus.live.value = AppBus.live.value.copy(partialRu = text, partialTranslations = tr)
            })
            pipeline = pipe

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 4))
                .build()
            record = rec
            writer = WavWriter(java.io.File(dir, "audio.wav"))
            sampler.start(scope)
            rec.startRecording()
            AppBus.live.value = AppBus.live.value.copy(status = "recording")
            AppBus.log("Recording. Output: ${dir.path}")

            val buf = FloatArray(SAMPLE_RATE / 10)
            var lastUi = 0L
            while (scope.isActive && job?.isActive == true) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    AppBus.log("AudioRecord error $n; stopping")
                    break
                }
                if (n == 0) continue
                writer.write(buf, n)
                pipe.feed(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                val now = System.nanoTime()
                if (now - lastUi > 500_000_000L) {
                    lastUi = now
                    AppBus.live.value = AppBus.live.value.copy(
                        elapsedSec = (now - startNs) / 1e9,
                        queueDepth = pipe.queueDepth,
                    )
                }
            }
        } catch (e: Exception) {
            AppBus.log("Live error: ${e.javaClass.simpleName}: ${e.message}")
        } finally { withContext(NonCancellable) {
            // Must not be cancellable: Stop cancels this coroutine, and the pipeline still has to
            // finish its last chunk, translate it and write report.txt.
            runCatching { record?.stop() }
            record?.release()
            writer?.close()
            AppBus.live.value = AppBus.live.value.copy(status = "finishing")
            val audioSec = (System.nanoTime() - startNs) / 1e9
            try {
                pipeline?.finish()
                sampler.stop()
                val report = logger.finish(
                    mapOf(
                        "device" to "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        "mode" to "live",
                        "model" to spec.id,
                        "targets" to targets.joinToString(","),
                    ),
                    audioSec, audioSec, pipeline?.modelLoadMs ?: 0.0, pipeline?.maxQueueDepth ?: 0, sampler,
                )
                AppBus.log(report)
            } catch (e: Exception) {
                AppBus.log("Report error: ${e.message}")
            }
            translator?.close()
            // Automatic end-of-recording summary + title. Only runs when the user has entered an API key and left
            // auto-summary on. Still in the foreground service so Android does not freeze the network call.
            val llm = LlmPrefs.load(this@LiveService)
            if (llm.ready && llm.autoSummary && AppBus.live.value.captions.isNotEmpty()) {
                AppBus.live.value = AppBus.live.value.copy(status = "summarizing")
                AppBus.log("Summarizing with ${llm.provider.label} (${AppBus.live.value.captions.size} captions of text, no audio)")
                kotlinx.coroutines.withTimeoutOrNull(150_000) { Summarizer.run(this@LiveService, llm, final = true) }
            }
            if (wakeLock.isHeld) wakeLock.release()
            AppBus.live.value = AppBus.live.value.copy(running = false, status = "idle")
            stopSelf()
        } }
    }

    private fun stopCapture() {
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.arislushai.STOP"
        const val EXTRA_MODEL = "model"
        const val EXTRA_TARGETS = "targets"
        private const val CHANNEL = "recording"

        fun start(ctx: Context, modelId: String, targets: List<String>) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, LiveService::class.java)
                    .putExtra(EXTRA_MODEL, modelId)
                    .putExtra(EXTRA_TARGETS, targets.joinToString(",")),
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LiveService::class.java).setAction(ACTION_STOP))
        }
    }
}
