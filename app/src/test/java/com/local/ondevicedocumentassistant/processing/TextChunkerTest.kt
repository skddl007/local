package com.local.ondevicedocumentassistant.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test
    fun preservesPageAndOverlap() {
        val text = (1..650).joinToString(" ") { "word$it" }

        val chunks = TextChunker.chunk(4, text)

        assertTrue(chunks.size >= 2)
        assertEquals(4, chunks.first().pageNumber)
        assertEquals(4, chunks[1].pageNumber)
        assertEquals(chunks.first().text.split(" ").takeLast(50), chunks[1].text.split(" ").take(50))
        assertEquals(0, chunks.first().chunkIndex)
    }

    @Test
    fun ignoresBlankText() {
        assertTrue(TextChunker.chunk(1, " \n\t ").isEmpty())
    }
}
