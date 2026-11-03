package app.arislushai

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * ML Kit on-device translation, Russian -> target ("en" or "id").
 * Russian -> Indonesian is pivoted through English by ML Kit itself.
 * Models are downloaded once (needs internet); afterwards translate() works offline.
 */
class MlKitTranslator {
    private val translators = mutableMapOf<String, Translator>()

    private fun language(target: String): String = when (target) {
        "en" -> TranslateLanguage.ENGLISH
        "id" -> TranslateLanguage.INDONESIAN
        else -> error("Unsupported target: $target")
    }

    private fun get(target: String): Translator = translators.getOrPut(target) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.RUSSIAN)
                .setTargetLanguage(language(target))
                .build()
        )
    }

    suspend fun ensureModel(target: String) {
        get(target).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    suspend fun translate(text: String, target: String): String = get(target).translate(text).await()

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}
