package com.routeassistant.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.routeassistant.app.util.AppResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device ML Kit Text Recognition.
 * - Image never leaves the device. No AI / cloud upload.
 * - Returns raw recognised text; parsing into addresses happens in
 *   OcrTextParser, and saving happens ONLY after user confirmation.
 */
object OcrHelper {

    suspend fun recognise(bitmap: Bitmap): AppResult<String> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val text: String = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { visionText -> cont.resume(visionText.text) }
                    // Do NOT swallow the failure as "" - that misreports a real
                    // ML Kit error as "No text found". Propagate it instead.
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
            try { recognizer.close() } catch (_: Exception) { /* ignore */ }
            if (text.isBlank()) {
                AppResult.Err("No text found in this image. Try a clearer screenshot.")
            } else {
                AppResult.Ok(text)
            }
        } catch (e: Exception) {
            AppResult.Err("OCR failed: ${e.message ?: "unknown error"}. Try again with a clearer screenshot.", e)
        }
    }
}
