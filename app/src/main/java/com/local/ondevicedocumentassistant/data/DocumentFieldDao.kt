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

    @Query(
        """
        SELECT f.*, d.docType AS document_type
        FROM document_fields f
        INNER JOIN documents d ON d.id = f.document_id
        WHERE lower(f.field_name) IN (:fieldNames)
        ORDER BY f.confidence DESC, f.id
        """,
    )
    suspend fun findByFieldNames(fieldNames: List<String>): List<FieldLookup>
}

data class FieldLookup(
    val id: Long,
    @androidx.room.ColumnInfo(name = "document_id") val documentId: Long,
    @androidx.room.ColumnInfo(name = "field_name") val fieldName: String,
    @androidx.room.ColumnInfo(name = "field_value") val fieldValue: String,
    val confidence: Float,
    @androidx.room.ColumnInfo(name = "document_type") val documentType: String,
)
