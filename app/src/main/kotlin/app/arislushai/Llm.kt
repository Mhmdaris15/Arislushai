package app.arislushai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional LLM features (summaries and recording titles). PRIVACY: only transcript TEXT is sent, never audio, and only
 * when a key has been entered AND the user presses Summarize or (if enabled) a recording ends. Nothing is sent otherwise.
 */
enum class Provider(val label: String, val defaultModel: String) {
    GEMINI("Gemini", "gemini-3.1-flash-lite"),
    OPENROUTER("OpenRouter", "anthropic/claude-haiku-5.5"),
}

data class LlmConfig(
    val provider: Provider = Provider.GEMINI,
    val apiKey: String = "",
    val model: String = Provider.GEMINI.defaultModel,
    val language: String = "English",
    val autoSummary: Boolean = true,
) {
    val ready: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()
}

/** API keys are encrypted with a non-exportable Android Keystore AES key before they touch disk. */
object SecretStore {
    private const val ALIAS = "arislushai_llm_keys"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? = runCatching {
        val b = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrNull()
}

object LlmPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("llm", Context.MODE_PRIVATE)

    fun load(ctx: Context): LlmConfig {
        val p = prefs(ctx)
        val prov = runCatching { Provider.valueOf(p.getString("provider", "GEMINI")!!) }.getOrDefault(Provider.GEMINI)
        return load(ctx, prov)
    }

    fun load(ctx: Context, prov: Provider): LlmConfig {
        val p = prefs(ctx)
        return LlmConfig(
            provider = prov,
            apiKey = p.getString("key_$prov", null)?.let { SecretStore.decrypt(it) }.orEmpty(),
            model = p.getString("model_$prov", prov.defaultModel) ?: prov.defaultModel,
            language = p.getString("lang", "English") ?: "English",
            autoSummary = p.getBoolean("auto", true),
        )
    }

    fun save(ctx: Context, c: LlmConfig) {
        prefs(ctx).edit().apply {
            putString("provider", c.provider.name)
            putString("model_${c.provider}", c.model)
            putString("lang", c.language)
            putBoolean("auto", c.autoSummary)
            if (c.apiKey.isBlank()) remove("key_${c.provider}") else putString("key_${c.provider}", SecretStore.encrypt(c.apiKey.trim()))
        }.apply()
    }
}

data class Summary(val title: String, val text: String)

class LlmException(message: String, val code: Int = 0) : Exception(message)

object LlmClient {
    private fun http(url: String, method: String, headers: Map<String, String>, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(300))
                throw LlmException("HTTP $code: ${msg.take(300)}", code)
            }
            return text
        } catch (e: java.io.IOException) {
            throw LlmException("Network error: ${e.message ?: e.javaClass.simpleName}. Are you offline?")
        } finally {
            conn.disconnect()
        }
    }

    /** Overload and rate-limit answers are usually gone a few seconds later; anything else (bad key, retired model) is not. */
    internal fun isTransient(code: Int) = code == 429 || code in 500..599

    /** One chat completion, retried up to 3 times (2 s, then 4 s apart) on transient provider errors. */
    suspend fun complete(cfg: LlmConfig, system: String, user: String): String {
        var wait = 2_000L
        for (attempt in 1..3) {
            try {
                return completeOnce(cfg, system, user)
            } catch (e: LlmException) {
                if (!isTransient(e.code) || attempt == 3) throw e
            }
            kotlinx.coroutines.delay(wait)
            wait *= 2
        }
        throw LlmException("unreachable")
    }

    /** The key travels in a header, never in the URL, and is never logged. */
    private suspend fun completeOnce(cfg: LlmConfig, system: String, user: String): String = withContext(Dispatchers.IO) {
        when (cfg.provider) {
            Provider.GEMINI -> {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 2048))
                val raw = http(
                    "https://generativelanguage.googleapis.com/v1beta/models/${cfg.model.removePrefix("models/")}:generateContent",
                    "POST", mapOf("x-goog-api-key" to cfg.apiKey), body.toString(),
                )
                val j = JSONObject(raw)
                val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val text = buildString { if (parts != null) for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
                if (text.isBlank()) throw LlmException("Empty reply" + (j.optJSONObject("promptFeedback")?.optString("blockReason")?.let { " (blocked: $it)" } ?: ""))
                text
            }
            Provider.OPENROUTER -> {
                val body = JSONObject()
                    .put("model", cfg.model)
                    .put("temperature", 0.3)
                    .put("max_tokens", 1500)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                val raw = http(
                    "https://openrouter.ai/api/v1/chat/completions", "POST",
                    mapOf("Authorization" to "Bearer ${cfg.apiKey}", "X-Title" to "Арислушай"), body.toString(),
                )
                val text = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                if (text.isBlank()) throw LlmException("Empty reply from model ${cfg.model}")
                text
            }
        }
    }

    /** Model ids offered by the provider. OpenRouter's list is public; Gemini's needs the key. */
    suspend fun listModels(cfg: LlmConfig): List<String> = withContext(Dispatchers.IO) {
        when (cfg.provider) {
            Provider.OPENROUTER -> {
                val data = JSONObject(http("https://openrouter.ai/api/v1/models", "GET", emptyMap(), null)).getJSONArray("data")
                (0 until data.length()).map { data.getJSONObject(it).getString("id") }.sorted()
            }
            Provider.GEMINI -> {
                if (cfg.apiKey.isBlank()) throw LlmException("Save a Gemini API key first; Google only lists models for a valid key.")
                val models = JSONObject(http("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", "GET", mapOf("x-goog-api-key" to cfg.apiKey), null))
                    .optJSONArray("models") ?: JSONArray()
                (0 until models.length()).map { models.getJSONObject(it) }
                    .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } ?: true }
                    .map { it.getString("name").removePrefix("models/") }.sorted()
            }
        }
    }

    suspend fun test(cfg: LlmConfig): String = complete(cfg, "Reply with the single word OK.", "ping").trim().take(60)

    private fun systemPrompt(language: String) =
        "You summarize university lectures and seminars. The input is an automatic speech-recognition transcript in Russian; " +
            "it can contain recognition errors, missing punctuation and filler words. Do not invent facts that are not in the " +
            "transcript, and skip passages that are unclear. Write in $language. Reply in exactly this format and nothing else:\n" +
            "TITLE: <a specific title of 3 to 8 words>\n" +
            "SUMMARY:\n- <bullet>\n" +
            "Use 3 to 8 bullets covering the main topics and arguments, key terms with a short explanation, important numbers, " +
            "dates and names, and any assignments, deadlines or exam hints. If the transcript is too short or unclear, say so in one bullet."

    suspend fun summarize(cfg: LlmConfig, transcript: String, previousSummary: String?): Summary {
        val user = if (previousSummary.isNullOrBlank()) "Transcript:\n$transcript"
        else "Current summary of the recording so far:\n$previousSummary\n\nNew transcript since that summary:\n$transcript\n\n" +
            "Update the summary so it covers the whole recording, keeping it concise."
        return parse(complete(cfg, systemPrompt(cfg.language), user))
    }

    internal fun parse(reply: String): Summary {
        // Models often decorate the labels with markdown bold, before and/or after the colon.
        val title = Regex("(?im)^\\s*\\**TITLE\\**\\s*:\\**\\s*(.+)$").find(reply)?.groupValues?.get(1)?.trim()?.trim('*', '"', '«', '»', ' ').orEmpty()
        val body = Regex("(?is)SUMMARY\\**\\s*:\\**\\s*(.*)$").find(reply)?.groupValues?.get(1)?.trim() ?: reply.trim()
        return Summary(title.ifBlank { body.lineSequence().firstOrNull().orEmpty().trimStart('-', ' ', '*').take(60) }.take(80), body)
    }
}

/** Summary state shown on the Live tab (one recording at a time). */
data class SummaryUi(
    val running: Boolean = false,
    val title: String = "",
    val text: String = "",
    val updatedAtMs: Long = 0L,
    val error: String = "",
    /** How many captions the current summary already covers; later presses only send what is new. */
    val covered: Int = 0,
)

object SummaryHub {
    val state = MutableStateFlow(SummaryUi())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

object Summarizer {
    private const val BATCH_CHARS = 40_000

    fun transcriptOf(captions: List<CaptionItem>): String =
        captions.joinToString("\n") { "[%02d:%02d] %s".format(it.startSec.toInt() / 60, it.startSec.toInt() % 60, it.ru) }

    private fun batches(captions: List<CaptionItem>): List<List<CaptionItem>> {
        val out = mutableListOf<List<CaptionItem>>()
        var cur = mutableListOf<CaptionItem>()
        var size = 0
        for (c in captions) {
            if (size + c.ru.length > BATCH_CHARS && cur.isNotEmpty()) { out += cur; cur = mutableListOf(); size = 0 }
            cur += c; size += c.ru.length + 8
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** Long recordings are summarized batch by batch, each call seeing the running summary, so prompts stay bounded. */
    suspend fun summarizeAll(cfg: LlmConfig, captions: List<CaptionItem>, previous: String?): Summary {
        var running = previous
        var last: Summary? = null
        for (b in batches(captions)) {
            last = LlmClient.summarize(cfg, transcriptOf(b), running)
            running = last.text
        }
        return last ?: throw LlmException("Nothing to summarize")
    }

    /** Live button or end-of-recording. final=true re-reads the whole recording instead of extending the last summary. */
    suspend fun run(ctx: Context, cfg: LlmConfig, final: Boolean) {
        val before = SummaryHub.state.value
        if (before.running) return
        fun fail(msg: String) { SummaryHub.state.value = SummaryHub.state.value.copy(running = false, error = msg) }
        if (!cfg.ready) return fail("Add an API key in the AI tab first.")
        val live = AppBus.live.value
        if (live.captions.isEmpty()) return fail("Nothing to summarize yet.")
        val from = if (final || before.text.isBlank()) 0 else before.covered.coerceIn(0, live.captions.size)
        val fresh = live.captions.drop(from)
        if (fresh.isEmpty()) return fail("Nothing new since the last summary.")
        SummaryHub.state.value = before.copy(running = true, error = "")
        try {
            val res = summarizeAll(cfg, fresh, if (from == 0) null else before.text)
            SummaryHub.state.value = SummaryUi(false, res.title, res.text, System.currentTimeMillis(), "", live.captions.size)
            val fd = ctx.getExternalFilesDir(null)
            if (fd != null && live.sessionDir.isNotBlank()) SummaryStore.save(fd, live.sessionDir, res, cfg)
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** For a saved recording that has no summary yet. Returns an error message, or null on success. */
    suspend fun runSaved(ctx: Context, dirName: String, cfg: LlmConfig): String? {
        if (!cfg.ready) return "Add an API key in the AI tab first."
        val fd = ctx.getExternalFilesDir(null) ?: return "Storage unavailable."
        val captions = HistoryRepo.load(fd, dirName)
        if (captions.isEmpty()) return "This recording has no captions."
        return try {
            SummaryStore.save(fd, dirName, summarizeAll(cfg, captions, null), cfg)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
}

/** `summary.json` sits next to results.jsonl in the recording's folder, so deleting the recording deletes it too. */
object SummaryStore {
    fun save(filesDir: File, dirName: String, s: Summary, cfg: LlmConfig) {
        val dir = File(File(filesDir, "runs"), dirName)
        if (!dir.isDirectory) return
        File(dir, "summary.json").writeText(
            JSONObject().put("title", s.title).put("summary", s.text).put("language", cfg.language)
                .put("provider", cfg.provider.name).put("model", cfg.model).put("updated_at", System.currentTimeMillis()).toString(),
            Charsets.UTF_8,
        )
    }

    fun load(dir: File): Summary? {
        val f = File(dir, "summary.json")
        if (!f.exists()) return null
        return runCatching { JSONObject(f.readText(Charsets.UTF_8)).let { Summary(it.optString("title"), it.optString("summary")) } }.getOrNull()
    }
}
