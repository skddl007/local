package com.local.ondevicedocumentassistant.data

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import com.local.ondevicedocumentassistant.processing.DocumentProcessingWorker

class DocumentRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(appContext).documentDao()

    fun observeDocuments(): Flow<List<DocumentEntity>> = dao.observeAll()

    suspend fun addUri(uri: Uri) {
        val value = uri.toString()
        if (dao.findByUri(value) == null) {
            val now = System.currentTimeMillis()
            val documentId = dao.insert(DocumentEntity(fileUri = value, createdAt = now, updatedAt = now))
            val request = OneTimeWorkRequestBuilder<DocumentProcessingWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(DocumentProcessingWorker.KEY_DOCUMENT_ID, documentId)
                        .putString(DocumentProcessingWorker.KEY_URI, value)
                        .build(),
                )
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                "document_processing_$documentId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}