package com.local.ondevicedocumentassistant.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrProcessor : AutoCloseable {
    private val recognizers = mutableListOf<com.google.mlkit.vision.text.TextRecognizer>()

    suspend fun process(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        val firstPass = recognizer(TextRecognizerOptions.DEFAULT_OPTIONS)
        firstPass.process(image)
            .addOnSuccessListener { result ->
                val script = detectScript(result.text)
                val selected = if (script == Script.LATIN) firstPass else recognizer(script.options())
                selected.process(image)
                    .addOnSuccessListener { selectedResult -> continuation.resume(selectedResult.text) }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
            .addOnFailureListener { error -> continuation.resumeWithException(error) }
    }

    override fun close() {
        recognizers.forEach { it.close() }
        recognizers.clear()
    }

    private fun recognizer(options: com.google.mlkit.vision.text.TextRecognizerOptionsInterface) =
        TextRecognition.getClient(options).also(recognizers::add)

    private fun detectScript(text: String): Script = when {
        text.any { it in '\u0900'..'\u097F' } -> Script.DEVANAGARI
        text.any { it in '\u4E00'..'\u9FFF' } -> Script.CHINESE
        text.any { it in '\u3040'..'\u30FF' } -> Script.JAPANESE
        text.any { it in '\uAC00'..'\uD7AF' } -> Script.KOREAN
        else -> Script.LATIN
    }
}

private enum class Script {
    LATIN,
    DEVANAGARI,
    CHINESE,
    JAPANESE,
    KOREAN;

    fun options(): com.google.mlkit.vision.text.TextRecognizerOptionsInterface = when (this) {
        LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
        DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
        CHINESE -> ChineseTextRecognizerOptions.Builder().build()
        JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
        KOREAN -> KoreanTextRecognizerOptions.Builder().build()
    }
}