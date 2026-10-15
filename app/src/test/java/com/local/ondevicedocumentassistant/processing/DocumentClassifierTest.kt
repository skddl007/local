package com.local.ondevicedocumentassistant.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentClassifierTest {
    @Test
    fun extractsValidPassportMrzFields() {
        val result = DocumentClassifier.classify(
            """
            P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
            L898902C36UTO7408122F1204159ZE184226B<<<<<10
            """.trimIndent(),
        )

        assertEquals("PASSPORT", result.documentType)
        assertEquals("UTO", result.country)
        assertTrue(result.fields.any { it.name == "PASSPORT_NUMBER" && it.value == "L898902C3" })
        assertTrue(result.fields.any { it.name == "SURNAME" && it.value == "ERIKSSON" })
    }

    @Test
    fun classifiesNationalIdentifiers() {
        val result = DocumentClassifier.classify("Government Aadhaar: 2345 6789 0123")

        assertEquals("AADHAAR", result.documentType)
        assertEquals("IN", result.country)
        assertEquals("234567890123", result.fields.single().value)
    }
}
