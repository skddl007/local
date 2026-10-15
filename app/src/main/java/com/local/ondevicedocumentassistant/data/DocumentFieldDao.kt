package com.local.ondevicedocumentassistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DocumentFieldDao {
    @Insert
    suspend fun insertAll(fields: List<DocumentFieldEntity>)

    @Query("DELETE FROM document_fields WHERE document_id = :documentId")
    suspend fun deleteForDocument(documentId: Long)

    @Query("SELECT * FROM document_fields WHERE document_id = :documentId ORDER BY field_name")
    suspend fun findForDocument(documentId: Long): List<DocumentFieldEntity>
}
