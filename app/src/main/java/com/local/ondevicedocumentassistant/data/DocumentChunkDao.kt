package com.local.ondevicedocumentassistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DocumentChunkDao {
    @Insert
    suspend fun insertAll(chunks: List<DocumentChunkEntity>)

    @Query("DELETE FROM document_chunks WHERE document_id = :documentId")
    suspend fun deleteForDocument(documentId: Long)

    @Query("SELECT * FROM document_chunks WHERE document_id = :documentId ORDER BY page_number, chunk_index")
    suspend fun findForDocument(documentId: Long): List<DocumentChunkEntity>
}