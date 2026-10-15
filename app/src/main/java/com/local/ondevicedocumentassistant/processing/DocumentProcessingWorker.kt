package com.local.ondevicedocumentassistant.processing

import android.content.Context
import android.net.Uri
import android.app.ActivityManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.local.ondevicedocumentassistant.data.AppDatabase

class DocumentProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val documentId = inputData.getLong(KEY_DOCUMENT_ID, -1L)
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse)
        if (documentId < 0 || uri == null) return Result.failure()

        val database = AppDatabase.get(applicationContext)
        val documentDao = database.documentDao()
        val chunkDao = database.documentChunkDao()
        val fieldDao = database.documentFieldDao()
        return try {
            documentDao.updateProcessingStatus(documentId, "PROCESSING")
            val processor = DocumentProcessor(
                applicationContext.contentResolver,
                chunkDao,
                (applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice,
            )
            val isPdf = applicationContext.contentResolver.getType(uri) == "application/pdf"
            if (isPdf) {
                processor.processPdf(documentId, uri) { page, total ->
                    setProgress(workDataOf(KEY_PAGE to page, KEY_TOTAL to total))
                }
            } else {
                processor.processImage(documentId, uri)
                setProgress(workDataOf(KEY_PAGE to 1, KEY_TOTAL to 1))
            }
            val text = chunkDao.findForDocument(documentId).joinToString("\n") { it.chunkText }
            val classification = DocumentClassifier.classify(text)
            fieldDao.deleteForDocument(documentId)
            if (classification.fields.isNotEmpty()) {
                fieldDao.insertAll(classification.fields.map {
                    com.local.ondevicedocumentassistant.data.DocumentFieldEntity(
                        documentId = documentId,
                        fieldName = it.name,
                        fieldValue = it.value,
                        confidence = it.confidence,
                    )
                })
            }
            documentDao.updateClassification(documentId, classification.documentType, classification.country)
            documentDao.updateProcessingStatus(documentId, "COMPLETED")
            Result.success()
        } catch (error: OutOfMemoryError) {
            chunkDao.deleteForDocument(documentId)
            documentDao.updateProcessingStatus(documentId, "FAILED", "out_of_memory")
            Result.failure(workDataOf(KEY_ERROR to "out_of_memory"))
        } catch (error: Throwable) {
            chunkDao.deleteForDocument(documentId)
            documentDao.updateProcessingStatus(documentId, "FAILED", error.message ?: "processing_failed")
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: "processing_failed")))
        }
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_URI = "uri"
        const val KEY_PAGE = "page"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
    }
}