package app.arislushai

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

const val SAMPLE_RATE = 16000

/** Longest continuous speech before the pipeline cuts a chunk itself (seconds). */
const val FORCE_CUT_SEC = 6L

private inline fun <T> logged(what: String, block: () -> T): T {
    AppBus.log("creating $what ...")
    val t0 = System.nanoTime()
    val r = block()
    AppBus.log("created $what in %.0f ms".format((System.nanoTime() - t0) / 1e6))
    return r
}

data class SegmentResult(
    val index: Int,
    /** Chunk start/duration in audio time (seconds from the start of the run). Chunk times, not word times. */
    val startSec: Double,
    val durSec: Double,
    val ru: String,
    val decodeMs: Double,
    val translations: Map<String, String>,
    val translateMs: Map<String, Double>,
    /** Time the finished segment waited in the queue before decoding started. */
    val queueWaitMs: Double,
    /** From VAD emitting the segment to the last translation finished. Excludes VAD silence wait. */
    val postSegmentMs: Double,
    /** Capture clock minus segment end, at completion: how far captions trail the audio (includes VAD wait). */
    val lagSec: Double,
    val queueDepth: Int,
)

/**
 * VAD -> bounded speech chunks -> offline recognizer -> ML Kit translation.
 * feed() must be called from one thread. Decoding/translation run on a single worker coroutine,
 * so a slow model creates a visible queue instead of dropping audio.
 */
class Pipeline(
    filesDir: File,
    spec: ModelSpec,
    private val targets: List<String>,
    private val translator: MlKitTranslator?,
    scope: CoroutineScope,
    private val onResult: (SegmentResult) -> Unit,
    /** Tentative text for the speech that is still in progress (not final, not translated). */
    private val onPartial: (String, Map<String, String>) -> Unit = { _, _ -> },
    numThreads: Int = 4,
    // Chunk latency is bounded by this: a caption cannot appear before its chunk ends. At 15 s the first
    // live test showed 14-18 s chunks and ~17-20 s caption delay although decoding was only ~1.7 s per chunk.
    maxSpeechSec: Float = 6f,
) {
    private val vad = logged("VAD") { Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = Models.vadPath(filesDir),
                threshold = 0.35f, // 0.5 clipped quiet word endings on the first test clip; tune on real lecture audio
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.2f,
                windowSize = 512,
                maxSpeechDuration = maxSpeechSec,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        ),
    ) }
    val modelLoadMs: Double
    private val recognizer: OfflineRecognizer

    init {
        val t0 = System.nanoTime()
        recognizer = logged("recognizer ${spec.id}") {
            OfflineRecognizer(null, Models.recognizerConfig(filesDir, spec, numThreads))
        }
        modelLoadMs = (System.nanoTime() - t0) / 1e6
    }

    private class Pending(val index: Int, val startSample: Long, val samples: FloatArray, val queuedAtNs: Long)

    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private val depth = AtomicInteger(0)
    @Volatile var maxQueueDepth = 0
        private set
    @Volatile private var fedSamples = 0L
    private var nextIndex = 0

    val queueDepth: Int get() = depth.get()
    val fedSeconds: Double get() = fedSamples / SAMPLE_RATE.toDouble()

    /** Latest in-progress audio awaiting a tentative decode; finals always take priority over it. */
    private val partialRef = AtomicReference<FloatArray?>(null)

    private val worker: Job = scope.launch(Dispatchers.Default) {
        while (true) {
            val r = queue.tryReceive()
            if (r.isSuccess) {
                process(r.getOrThrow())
                continue
            }
            if (r.isClosed) break
            val partial = partialRef.getAndSet(null)
            if (partial != null) {
                processPartial(partial)
                continue
            }
            delay(15)
        }
    }

    // Rolling copy of the most recent audio so each VAD segment can be padded with its surrounding
    // context. The VAD trims quiet word endings ("я тебя люблю" was cut to "я тебя"), which breaks recognition.
    private val ringCap = 90 * SAMPLE_RATE
    private val ring = FloatArray(ringCap)
    private var lastEndAbs = 0L

    fun feed(samples: FloatArray) {
        var pos = (fedSamples % ringCap).toInt()
        for (s in samples) {
            ring[pos] = s
            if (++pos == ringCap) pos = 0
        }
        fedSamples += samples.size
        vad.acceptWaveform(samples)
        drainVad()
        offerPartial()
    }

    private var speechStartAbs = -1L
    private var lastPartialAtAbs = 0L

    /** While speech is ongoing, offer the audio so far (at most once per second, last 12 s) for a tentative decode. */
    private fun offerPartial() {
        if (!vad.isSpeechDetected()) {
            speechStartAbs = -1
            return
        }
        if (speechStartAbs < 0) speechStartAbs = maxOf(fedSamples - SAMPLE_RATE / 2, lastEndAbs)
        // The VAD does not reliably enforce its max duration (a 17.6 s chunk appeared with max=6 s), and a caption
        // cannot appear before its chunk ends, so bound chunk length here, cutting at the quietest moment.
        if (fedSamples - speechStartAbs >= FORCE_CUT_SEC * SAMPLE_RATE) {
            forceCut()
            return
        }
        if (fedSamples - lastPartialAtAbs >= SAMPLE_RATE) {
            lastPartialAtAbs = fedSamples
            val audio = slice(maxOf(speechStartAbs, fedSamples - 12L * SAMPLE_RATE), fedSamples)
            if (audio.size > SAMPLE_RATE / 2) partialRef.set(audio)
        }
    }

    private fun forceCut() {
        val cut = quietestPoint(fedSamples - (2.5 * SAMPLE_RATE).toLong(), fedSamples - (0.3 * SAMPLE_RATE).toLong())
        val from = maxOf(speechStartAbs - (0.3 * SAMPLE_RATE).toLong(), lastEndAbs)
        val chunk = slice(from, cut)
        if (chunk.isEmpty()) return
        lastEndAbs = from + chunk.size
        speechStartAbs = lastEndAbs
        partialRef.set(null)
        enqueue(from, chunk)
    }

    /** Centre of the lowest-energy 100 ms frame in [lo, hi): least likely to be inside a word. */
    private fun quietestPoint(lo: Long, hi: Long): Long {
        val frame = SAMPLE_RATE / 10
        var best = hi
        var bestEnergy = Float.MAX_VALUE
        var s = maxOf(lo, speechStartAbs + SAMPLE_RATE) // keep chunks at least ~1 s long
        while (s + frame <= hi) {
            var e = 0f
            for (x in slice(s, s + frame)) e += x * x
            if (e < bestEnergy) {
                bestEnergy = e
                best = s + frame / 2
            }
            s += frame / 2
        }
        return best
    }

    private fun enqueue(start: Long, chunk: FloatArray) {
        val d = depth.incrementAndGet()
        if (d > maxQueueDepth) maxQueueDepth = d
        queue.trySend(Pending(nextIndex++, start, chunk, System.nanoTime()))
    }

    private suspend fun processPartial(audio: FloatArray) {
        val stream = recognizer.createStream()
        val text = try {
            stream.acceptWaveform(audio, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
        if (text.isBlank() || depth.get() != 0) return
        // Tentative translation too: ~0.2-0.5 s per language, so English/Indonesian follow within ~1-2 s of speech.
        val tr = mutableMapOf<String, String>()
        if (translator != null) {
            for (t in targets) {
                if (depth.get() != 0) return // a final chunk is waiting; do not delay it
                tr[t] = try { translator.translate(text, t) } catch (e: Exception) { "" }
            }
        }
        // A final chunk may have been queued while we worked; its result replaces this one.
        if (depth.get() == 0) onPartial(text, tr)
    }

    private fun slice(from: Long, to: Long): FloatArray {
        val lo = maxOf(from, fedSamples - ringCap, 0L)
        val hi = minOf(to, fedSamples)
        if (hi <= lo) return FloatArray(0)
        val out = FloatArray((hi - lo).toInt())
        var p = (lo % ringCap).toInt()
        for (i in out.indices) {
            out[i] = ring[p]
            if (++p == ringCap) p = 0
        }
        return out
    }

    /** End of input: flush the VAD, let the worker drain, release native resources. */
    suspend fun finish() {
        vad.flush()
        drainVad()
        queue.close()
        worker.join()
        recognizer.release()
        vad.release()
    }

    private fun drainVad() {
        while (!vad.empty()) {
            val seg: SpeechSegment = vad.front()
            vad.pop()
            // Pad 0.3 s before and 0.6 s after; never overlap the previous chunk's audio.
            val from = maxOf(seg.start - (0.3 * SAMPLE_RATE).toLong(), lastEndAbs)
            val chunk = slice(from, seg.start + seg.samples.size + (0.6 * SAMPLE_RATE).toLong())
            speechStartAbs = -1
            partialRef.set(null)
            // Empty = this VAD segment was already covered by forced cuts; do not decode it twice.
            if (chunk.size < SAMPLE_RATE / 5) continue
            lastEndAbs = from + chunk.size
            enqueue(from, chunk)
        }
    }

    private suspend fun process(p: Pending) {
        val start = System.nanoTime()
        val depthAtStart = depth.get()
        val stream = recognizer.createStream()
        val text = try {
            stream.acceptWaveform(p.samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
        val decodeMs = (System.nanoTime() - start) / 1e6

        val tr = mutableMapOf<String, String>()
        val trMs = mutableMapOf<String, Double>()
        if (text.isNotBlank() && translator != null) {
            for (t in targets) {
                val t0 = System.nanoTime()
                tr[t] = try {
                    translator.translate(text, t)
                } catch (e: Exception) {
                    "ERR: ${e.javaClass.simpleName}: ${e.message}"
                }
                trMs[t] = (System.nanoTime() - t0) / 1e6
            }
        }
        val end = System.nanoTime()
        val endSec = (p.startSample + p.samples.size) / SAMPLE_RATE.toDouble()
        depth.decrementAndGet()
        onResult(
            SegmentResult(
                index = p.index,
                startSec = p.startSample / SAMPLE_RATE.toDouble(),
                durSec = p.samples.size / SAMPLE_RATE.toDouble(),
                ru = text,
                decodeMs = decodeMs,
                translations = tr,
                translateMs = trMs,
                queueWaitMs = (start - p.queuedAtNs) / 1e6,
                postSegmentMs = (end - p.queuedAtNs) / 1e6,
                lagSec = fedSeconds - endSec,
                queueDepth = depthAtStart,
            )
        )
    }
}
