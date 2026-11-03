package app.arislushai

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

enum class Kind { NEMO_TRANSDUCER, NEMO_CTC, TRANSDUCER }

data class ModelSpec(val id: String, val kind: Kind)

/**
 * Models live in `<externalFilesDir>/models/<id>/` and are installed with `scripts/push-models.ps1` for now
 * (in-app download is on the roadmap). See MODELS.md for sources and licenses.
 */
object Models {
    val all = listOf(
        ModelSpec("giga-v3-transducer", Kind.NEMO_TRANSDUCER),
        ModelSpec("giga-v3-ctc", Kind.NEMO_CTC),
        ModelSpec("zipformer-ru-int8", Kind.TRANSDUCER),
    )

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    fun root(filesDir: File) = File(filesDir, "models")

    // v5 on purpose: the older silero_vad.onnx missed quiet/noisy speech at threshold 0.5 on the PC check.
    fun vadPath(filesDir: File): String = File(root(filesDir), "vad/silero_vad_v5.onnx").path

    fun isInstalled(filesDir: File, spec: ModelSpec) = File(root(filesDir), spec.id).isDirectory

    /** Prefer the int8 variant of a file family, fall back to the plain one. */
    private fun pick(dir: File, prefix: String): String {
        val files = dir.listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
        val chosen = files.firstOrNull { it.name.contains("int8") } ?: files.firstOrNull()
        return (chosen ?: error("No $prefix*.onnx in $dir")).path
    }

    fun recognizerConfig(filesDir: File, spec: ModelSpec, threads: Int): OfflineRecognizerConfig {
        val dir = File(root(filesDir), spec.id)
        val tokens = File(dir, "tokens.txt").path
        val model = when (spec.kind) {
            Kind.NEMO_TRANSDUCER, Kind.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = pick(dir, "encoder"),
                    decoder = pick(dir, "decoder"),
                    joiner = pick(dir, "joiner"),
                ),
                tokens = tokens,
                numThreads = threads,
                provider = "cpu",
                modelType = if (spec.kind == Kind.NEMO_TRANSDUCER) "nemo_transducer" else "transducer",
            )
            Kind.NEMO_CTC -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = pick(dir, "model")),
                tokens = tokens,
                numThreads = threads,
                provider = "cpu",
            )
        }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = model,
            decodingMethod = "greedy_search",
        )
    }
}
