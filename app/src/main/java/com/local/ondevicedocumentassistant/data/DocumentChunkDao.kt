package com.local.ondevicedocumentassistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.RawQuery
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface DocumentChunkDao {
    @Insert
    suspend fun insertAll(chunks: List<DocumentChunkEntity>)

    @Query("DELETE FROM document_chunks WHERE document_id = :documentId")
    suspend fun deleteForDocument(documentId: Long)

    @Query("SELECT * FROM document_chunks WHERE document_id = :documentId ORDER BY page_number, chunk_index")
    suspend fun findForDocument(documentId: Long): List<DocumentChunkEntity>

    @RawQuery
    suspend fun search(query: SupportSQLiteQuery): List<SearchChunk>
}

data class SearchChunk(
    val id: Long,
    @androidx.room.ColumnInfo(name = "document_id") val documentId: Long,
    @androidx.room.ColumnInfo(name = "page_number") val pageNumber: Int,
    @androidx.room.ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @androidx.room.ColumnInfo(name = "chunk_text") val chunkText: String,
    @androidx.room.ColumnInfo(name = "token_count") val tokenCount: Int,
    val embedding: ByteArray?,
    @androidx.room.ColumnInfo(name = "document_type") val documentType: String,
)