package com.local.ondevicedocumentassistant.query

import com.local.ondevicedocumentassistant.data.DocumentChunkDao
import com.local.ondevicedocumentassistant.data.DocumentFieldDao
import com.local.ondevicedocumentassistant.data.FieldLookup
import com.local.ondevicedocumentassistant.data.SearchChunk
import androidx.sqlite.db.SimpleSQLiteQuery

data class QueryAnswer(
    val text: String,
    val citations: List<QueryCitation> = emptyList(),
    val requiresAuthentication: Boolean = false,
)

data class QueryCitation(
    val documentType: String,
    val pageNumber: Int?,
)

class DocumentQueryEngine(
    private val fieldDao: DocumentFieldDao,
    private val chunkDao: DocumentChunkDao,
) {
    suspend fun answer(question: String): QueryAnswer {
        val normalized = question.trim()
        if (normalized.isEmpty()) return QueryAnswer("Please enter a question.")

        val fieldNames = fieldNamesFor(normalized)
        if (fieldNames.isNotEmpty()) {
            val fields = fieldDao.findByFieldNames(fieldNames)
            if (fields.isNotEmpty()) return fieldAnswer(fields)
        }

        val terms = normalized
            .lowercase()
            .split(TERM_SEPARATOR)
            .filter { it.length >= 2 }
            .distinct()
        if (terms.isEmpty()) return QueryAnswer("I could not find that in your documents.")

        val matches = runCatching {
            chunkDao.search(
                SimpleSQLiteQuery(
                    """
                    SELECT c.id, c.document_id, c.page_number, c.chunk_index, c.chunk_text,
                           c.token_count, c.embedding, d.docType AS document_type
                    FROM chunks_fts f
                    INNER JOIN document_chunks c ON c.id = f.rowid
                    INNER JOIN documents d ON d.id = c.document_id
                    WHERE chunks_fts MATCH ?
                    ORDER BY bm25(chunks_fts)
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(terms.joinToString(" ") { "$it*" }, 3),
                ),
            )
        }.getOrElse { emptyList() }
        if (matches.isEmpty()) return QueryAnswer("I could not find that in your documents.")

        return QueryAnswer(
            text = matches.joinToString("\n\n") { it.chunkText },
            citations = matches.map { QueryCitation(it.documentType, it.pageNumber) }.distinct(),
        )
    }

    private fun fieldAnswer(fields: List<FieldLookup>): QueryAnswer {
        val label = fields.first().fieldName.replace('_', ' ').lowercase()
        return QueryAnswer(
            text = fields.joinToString("\n") { "${it.fieldValue} ($label)" },
            citations = fields.map { QueryCitation(it.documentType, null) }.distinct(),
            requiresAuthentication = true,
        )
    }

    private fun fieldNamesFor(question: String): List<String> {
        val value = question.lowercase()
        return FIELD_TERMS
            .filterKeys { terms -> terms.any(value::contains) }
            .values
            .flatten()
            .distinct()
    }

    companion object {
        fun fieldNamesForTesting(question: String): List<String> {
            val value = question.lowercase()
            return FIELD_TERMS
                .filterKeys { terms -> terms.any(value::contains) }
                .values
                .flatten()
                .distinct()
        }

        private val TERM_SEPARATOR = Regex("""[^\p{L}\p{N}_]+""")
        private val FIELD_TERMS = linkedMapOf(
            listOf("aadhaar", "aadhar", "आधार") to listOf("AADHAAR_NUMBER"),
            listOf("pan number", "pan card", "pan") to listOf("PAN_NUMBER"),
            listOf("social security", "ssn") to listOf("US_SSN"),
            listOf("national insurance", "nino") to listOf("UK_NINO"),
            listOf("passport number", "passport no", "passport") to listOf("PASSPORT_NUMBER", "DOCUMENT_NUMBER"),
            listOf("surname", "last name", "family name", "उपनाम") to listOf("SURNAME"),
            listOf("given name", "first name", "नाम") to listOf("GIVEN_NAMES"),
            listOf("nationality", "नागरिकता") to listOf("NATIONALITY"),
            listOf("date of birth", "dob", "जन्म") to listOf("DATE_OF_BIRTH"),
            listOf("expiry", "expiration", "valid until") to listOf("EXPIRY"),
        )
    }
}
