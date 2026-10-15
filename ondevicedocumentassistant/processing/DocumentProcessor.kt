package com.local.ondevicedocumentassistant.processing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.local.ondevicedocumentassistant.data.DocumentChunkEntity
import com.local.ondevicedocumentassistant.data.DocumentChunkDao
import java.io.FileDescriptor
import kotlin.math.min

class DocumentProcessor(
    private val contentResolver: ContentResolver,
    private val chunkDao: DocumentChunkDao,
) {
    suspend fun processImage(documentId: Long, uri: Uri) {
        val bitmap = contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: error("Unable to decode image")
        val oriented = orient(bitmap, uri)
        try {
            val text = OcrProcessor().use { it.process(oriented) }
            storeChunks(documentId, 1, text)
        } finally {
            if (oriented !== bitmap) oriented.recycle()
            bitmap.recycle()
        }
    }

    suspend fun processPdf(documentId: Long, uri: Uri, onProgress: suspend (Int, Int) -> Unit) {
        val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open PDF")
        descriptor.use { file ->
            PdfRenderer(file).use { renderer ->
                OcrProcessor().use { ocr ->
                    val total = renderer.pageCount
                    for (pageNumber in 0 until total) {
                        renderer.openPage(pageNumber).use { page ->
                            val scale = min(150f / 72f, 2480f / page.width)
                                .coerceAtMost(3508f / page.height)
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                storeChunks(documentId, pageNumber + 1, ocr.process(bitmap))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                        onProgress(pageNumber + 1, total)
                    }
                }
            }
        }
    }

    private suspend fun storeChunks(documentId: Long, pageNumber: Int, text: String) {
        val chunks = TextChunker.chunk(pageNumber, text).map { chunk ->
            DocumentChunkEntity(
                documentId = documentId,
                pageNumber = chunk.pageNumber,
                chunkIndex = chunk.chunkIndex,
                chunkText = chunk.text,
                tokenCount = chunk.tokenCount,
            )
        }
        if (chunks.isNotEmpty()) chunkDao.insertAll(chunks)
    }

    private fun orient(bitmap: Bitmap, uri: Uri): Bitmap {
        val orientation = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply {
            postRotate(degrees)
        }, true)
    }
}

private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R = try {
    block(this)
} finally {
    close()
}