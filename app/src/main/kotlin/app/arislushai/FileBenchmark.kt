package app.arislushai

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a WAV through VAD -> recognizer -> translation.
 * paced=false: feed as fast as possible (measures raw throughput/RTF).
 * paced=true: feed at real-time speed (mimics a live lecture, shows whether a backlog builds).
 */
suspend fun runFileBenchmark(
    ctx: Context,
    spec: ModelSpec,
    wav: File,
    targets: List<String>,
    paced: Boolean,
): String = withContext(Dispatchers.Default) {
    val filesDir = ctx.getExternalFilesDir(null)!!
    val dir = newRunDir(ctx, "file-${spec.id}${if (paced) "-paced" else ""}")
    val logger = RunLogger(dir)
    val sampler = DeviceSampler(ctx, dir)
    var translator: MlKitTranslator? = null
    try {
        if (targets.isNotEmpty()) {
            translator = MlKitTranslator()
            for (t in targets) {
                AppBus.log("ML Kit: ensuring ru->$t model (needs internet only the first time)...")
                translator.ensureModel(t)
            }
        }
        AppBus.log("Loading ${spec.id} ...")
        val pipeline = Pipeline(filesDir, spec, targets, translator, this, { r ->
            logger.add(r)
            AppBus.log("#${r.index} [%.1fs+%.1fs] decode %.0fms: %s".format(r.startSec, r.durSec, r.decodeMs, r.ru))
            r.translations.forEach { (k, v) -> AppBus.log("    $k: $v") }
        })
        AppBus.log("Model loaded in %.0f ms".format(pipeline.modelLoadMs))

        WavReader(wav).use { reader ->
            val audioSec = reader.totalSamples / SAMPLE_RATE.toDouble()
            AppBus.log("Input %.1f s, paced=$paced".format(audioSec))
            sampler.start(this)
            val wallStart = System.nanoTime()
            var fed = 0L
            while (true) {
                val chunk = reader.read(SAMPLE_RATE / 10) ?: break
                pipeline.feed(chunk)
                fed += chunk.size
                if (paced) {
                    val dueNs = wallStart + fed * 1_000_000_000L / SAMPLE_RATE
                    val waitMs = (dueNs - System.nanoTime()) / 1_000_000
                    if (waitMs > 0) delay(waitMs)
                }
            }
            pipeline.finish()
            val wallSec = (System.nanoTime() - wallStart) / 1e9
            sampler.stop()
            val header = mapOf(
                "device" to "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "model" to spec.id,
                "input" to wav.name,
                "paced" to paced.toString(),
                "targets" to targets.joinToString(","),
            )
            val report = logger.finish(header, audioSec, wallSec, pipeline.modelLoadMs, pipeline.maxQueueDepth, sampler)
            AppBus.log(report)
            AppBus.log("Saved to ${dir.path}")
            report
        }
    } finally {
        sampler.stop()
        translator?.close()
    }
}
