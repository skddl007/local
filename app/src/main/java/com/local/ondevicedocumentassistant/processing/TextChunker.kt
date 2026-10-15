package com.local.ondevicedocumentassistant.processing

data class TextChunk(
    val pageNumber: Int,
    val chunkIndex: Int,
    val text: String,
    val tokenCount: Int,
)

object TextChunker {
    private const val CHUNK_WORDS = 300
    private const val OVERLAP_WORDS = 50

    fun chunk(pageNumber: Int, text: String): List<TextChunk> {
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()

        val chunks = mutableListOf<TextChunk>()
        var start = 0
        var index = 0
        while (start < words.size) {
            val end = minOf(start + CHUNK_WORDS, words.size)
            val chunkWords = words.subList(start, end)
            chunks += TextChunk(
                pageNumber = pageNumber,
                chunkIndex = index++,
                text = chunkWords.joinToString(" "),
                tokenCount = chunkWords.size,
            )
            if (end == words.size) break
            start = end - OVERLAP_WORDS
        }
        return chunks
    }
}