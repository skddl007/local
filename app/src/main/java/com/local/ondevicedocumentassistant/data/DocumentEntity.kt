package com.local.ondevicedocumentassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileUri: String,
    val docType: String = "UNKNOWN",
    val country: String? = null,
    val pageCount: Int = 1,
    val processingStatus: String = "QUEUED",
    val processingError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)