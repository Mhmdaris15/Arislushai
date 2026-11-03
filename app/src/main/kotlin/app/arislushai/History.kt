package app.arislushai

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** One saved recording, summarized for the History list. Captions are loaded only when a section is expanded. */
data class SavedSession(
    val dirName: String,
    val kind: String,
    val startedAtMs: Long,
    val model: String,
    val captionCount: Int,
    val durationSec: Double,
    val preview: String,
    /** LLM-generated title/summary when available; the History card falls back to the date otherwise. */
    val title: String? = null,
    val summary: String? = null,
)

/**
 * History is read straight from the per-run folders (`runs/<timestamp>-<kind>-<model>/results.jsonl`),
 * which the pipeline already appends to after every caption, so a crash never loses a recording.
 * The product app will move this into Room; the on-disk shape here is the same data.
 */
object HistoryRepo {
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private fun runsDir(filesDir: File) = File(filesDir, "runs")

    fun list(filesDir: File): List<SavedSession> =
        runsDir(filesDir).listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { summarize(it) }
            .sortedByDescending { it.startedAtMs }

    private fun summarize(dir: File): SavedSession? {
        val results = File(dir, "results.jsonl")
        if (!results.exists() || results.length() == 0L) return null
        val parts = dir.name.split("-", limit = 4)
        if (parts.size < 4) return null
        val started = runCatching { stamp.parse("${parts[0]}-${parts[1]}")!!.time }.getOrDefault(dir.lastModified())
        var count = 0
        var endSec = 0.0
        var preview = ""
        results.useLines { lines ->
            for (line in lines) {
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (o.optString("ru").isBlank()) continue
                if (count == 0) preview = o.optString("ru")
                count++
                endSec = o.optDouble("start_s") + o.optDouble("dur_s")
            }
        }
        if (count == 0) return null
        val s = SummaryStore.load(dir)
        return SavedSession(
            dir.name, parts[2], started, parts[3], count, endSec, preview,
            title = s?.title?.takeIf { it.isNotBlank() }, summary = s?.text?.takeIf { it.isNotBlank() },
        )
    }

    fun load(filesDir: File, dirName: String): List<CaptionItem> {
        val results = File(File(runsDir(filesDir), dirName), "results.jsonl")
        if (!results.exists()) return emptyList()
        val out = mutableListOf<CaptionItem>()
        results.useLines { lines ->
            for (line in lines) {
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val ru = o.optString("ru")
                if (ru.isBlank()) continue
                val tr = linkedMapOf<String, String>()
                for (key in o.keys()) {
                    if (key.startsWith("tr_") && !key.endsWith("_ms")) tr[key.removePrefix("tr_")] = o.optString(key)
                }
                out += CaptionItem(o.optInt("index"), o.optDouble("start_s"), ru, tr)
            }
        }
        return out
    }

    /** README requirement: deleting a session removes its audio and transcript. */
    fun delete(filesDir: File, dirName: String): Boolean {
        val dir = File(runsDir(filesDir), dirName)
        return dir.parentFile?.name == "runs" && dir.deleteRecursively()
    }
}
