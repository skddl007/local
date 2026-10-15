package com.local.ondevicedocumentassistant.processing

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer

class PdfPageCountChecker(private val contentResolver: ContentResolver) {
    fun count(uri: Uri): Int {
        val descriptor: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: error("Unable to open PDF")
        return descriptor.use { PdfRenderer(it).use(PdfRenderer::getPageCount) }
    }
}

enum class PdfIngestionStrategy {
    FULL_IMMEDIATE,
    FULL_BACKGROUND,
    FULL_WORK_MANAGER,
    LARGE_PDF_SELECTION,
}

object PdfIngestionStrategySelector {
    fun select(pageCount: Int): PdfIngestionStrategy = when {
        pageCount <= 2 -> PdfIngestionStrategy.FULL_IMMEDIATE
        pageCount <= 15 -> PdfIngestionStrategy.FULL_BACKGROUND
        pageCount <= 50 -> PdfIngestionStrategy.FULL_WORK_MANAGER
        else -> PdfIngestionStrategy.LARGE_PDF_SELECTION
    }
}