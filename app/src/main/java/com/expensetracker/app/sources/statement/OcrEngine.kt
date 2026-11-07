package com.expensetracker.app.sources.statement

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.expensetracker.app.domain.statement.OcrLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device OCR (ML Kit, bundled model; works offline). Returns visual rows of text. */
@Singleton
class OcrEngine @Inject constructor(@ApplicationContext private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun lines(uri: Uri): List<String> = lines(InputImage.fromFilePath(context, uri))

    suspend fun lines(bitmap: Bitmap): List<String> = lines(InputImage.fromBitmap(bitmap, 0))

    private suspend fun lines(image: InputImage): List<String> = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { text -> cont.resume(toLines(text)) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun toLines(text: Text): List<String> = OcrLayout.toLines(
        text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { OcrLayout.Box(line.text, it.left, it.top, it.right, it.bottom) }
        },
    )
}
