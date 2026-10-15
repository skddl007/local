package com.local.ondevicedocumentassistant.query

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentQueryEngineTest {
    @Test
    fun mapsEnglishAndHindiFieldTerms() {
        val fieldNames = DocumentQueryEngine.fieldNamesForTesting("What is my passport number?")
        assertEquals(listOf("PASSPORT_NUMBER", "DOCUMENT_NUMBER"), fieldNames)

        val hindiNames = DocumentQueryEngine.fieldNamesForTesting("मेरा जन्म कब है?")
        assertEquals(listOf("DATE_OF_BIRTH"), hindiNames)
    }
}
