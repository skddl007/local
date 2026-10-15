package com.local.ondevicedocumentassistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE fileUri = :fileUri LIMIT 1")
    suspend fun findByUri(fileUri: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(document: DocumentEntity): Long

    @Query("UPDATE documents SET processingStatus = :status, processingError = :error, updatedAt = :updatedAt WHERE id = :documentId")
    suspend fun updateProcessingStatus(documentId: Long, status: String, error: String? = null, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET docType = :docType, country = :country, updatedAt = :updatedAt WHERE id = :documentId")
    suspend fun updateClassification(
        documentId: Long,
        docType: String,
        country: String?,
        updatedAt: Long = System.currentTimeMillis(),
    )
}