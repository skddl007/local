package com.local.ondevicedocumentassistant.processing

data class ExtractedField(
    val name: String,
    val value: String,
    val confidence: Float,
)

data class ClassificationResult(
    val documentType: String,
    val country: String?,
    val fields: List<ExtractedField>,
)

object DocumentClassifier {
    fun classify(text: String): ClassificationResult {
        val normalized = text.replace('\u00A0', ' ').trim()
        val mrz = MrzParser.parse(normalized)
        if (mrz != null) {
            return ClassificationResult(
                documentType = if (mrz.documentCode.startsWith("P")) "PASSPORT" else "IDENTITY_CARD",
                country = mrz.nationality,
                fields = mrz.fields,
            )
        }

        val fields = buildList {
            Regex("""\b\d{4}\s?\d{4}\s?\d{4}\b""").find(normalized)?.let {
                add(ExtractedField("AADHAAR_NUMBER", it.value.replace(" ", ""), 0.9f))
            }
            Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""").find(normalized)?.let {
                add(ExtractedField("PAN_NUMBER", it.value, 0.9f))
            }
            Regex("""\b\d{3}-\d{2}-\d{4}\b""").find(normalized)?.let {
                add(ExtractedField("US_SSN", it.value, 0.85f))
            }
            Regex("""\b[A-CEGHJ-PR-TW-Z]{2}\s?\d{6}\s?[A-D]\b""", RegexOption.IGNORE_CASE)
                .find(normalized)?.let {
                    add(ExtractedField("UK_NINO", it.value.replace(" ", "").uppercase(), 0.8f))
                }
        }
        val type = when {
            fields.any { it.name == "AADHAAR_NUMBER" } ||
                normalized.contains("AADHAAR", ignoreCase = true) -> "AADHAAR"
            fields.any { it.name == "PAN_NUMBER" } ||
                normalized.contains("INCOME TAX", ignoreCase = true) -> "PAN"
            fields.any { it.name == "US_SSN" } ||
                normalized.contains("SOCIAL SECURITY", ignoreCase = true) -> "SSN"
            fields.any { it.name == "UK_NINO" } -> "DRIVING_LICENSE"
            normalized.contains("DRIVING LICENCE", ignoreCase = true) ||
                normalized.contains("DRIVER LICENSE", ignoreCase = true) -> "DRIVING_LICENSE"
            else -> "UNKNOWN"
        }
        val country = when (type) {
            "AADHAAR", "PAN" -> "IN"
            "SSN" -> "US"
            "DRIVING_LICENSE" -> if (fields.any { it.name == "UK_NINO" }) "GB" else null
            else -> null
        }
        return ClassificationResult(type, country, fields)
    }
}

data class MrzResult(
    val documentCode: String,
    val nationality: String?,
    val fields: List<ExtractedField>,
)

object MrzParser {
    private val checkWeights = intArrayOf(7, 3, 1)

    fun parse(text: String): MrzResult? {
        val lines = text.lines()
            .map { it.uppercase().replace(" ", "").replace('«', '<') }
            .filter { it.length >= 30 && it.all { char -> char.isLetterOrDigit() || char == '<' } }
        val td3 = lines.firstOrNull { it.length >= 44 && it.startsWith("P") }
            ?.let { line -> lines.dropWhile { it != line }.take(2).map(::fit) }
        val td1 = lines.firstOrNull { it.length >= 30 && (it.startsWith("I") || it.startsWith("A")) }
            ?.let { line -> lines.dropWhile { it != line }.take(3).map(::fit) }
        val parsed = when {
            td3?.size == 2 -> parseTd3(td3)
            td1?.size == 3 -> parseTd1(td1)
            else -> null
        } ?: return null
        return parsed.takeIf { it.fields.isNotEmpty() }
    }

    private fun parseTd3(lines: List<String>): MrzResult? {
        val first = lines[0]
        val second = lines[1]
        if (!validCheck(second, 0, 9) || !validCheck(second, 13, 19) ||
            !validCheck(second, 21, 27) || !validCheck(second, 28, 42) ||
            !validCompositeCheck(second)
        ) return null
        val names = first.substring(5, 44).split("<<", limit = 2)
        val surname = names.getOrNull(0)?.replace('<', ' ')?.trim().orEmpty()
        val given = names.getOrNull(1)?.replace('<', ' ')?.trim().orEmpty()
        val fields = listOfNotNull(
            field("SURNAME", surname, 0.95f),
            field("GIVEN_NAMES", given, 0.95f),
            field("PASSPORT_NUMBER", second.substring(0, 9).replace('<', ' ').trim(), 0.95f),
            field("NATIONALITY", second.substring(10, 13).replace('<', ' ').trim(), 0.95f),
            field("DATE_OF_BIRTH", second.substring(13, 19), 0.95f),
            field("SEX", second[20].takeUnless { it == '<' }?.toString(), 0.95f),
            field("EXPIRY", second.substring(21, 27), 0.95f),
            field("PERSONAL_NUMBER", second.substring(28, 42).replace('<', ' ').trim(), 0.9f),
        )
        return MrzResult("P", second.substring(10, 13), fields)
    }

    private fun parseTd1(lines: List<String>): MrzResult? {
        val first = lines[0]
        val second = lines[1]
        if (!validCheck(first, 5, 14) || !validCheck(second, 0, 7) ||
            !validCheck(second, 8, 15) || !validCheck(second, 18, 24)
        ) return null
        val names = lines[2].split("<<", limit = 2)
        val fields = listOfNotNull(
            field("SURNAME", names[0].replace('<', ' ').trim(), 0.9f),
            field("GIVEN_NAMES", names.getOrNull(1)?.replace('<', ' ')?.trim(), 0.9f),
            field("DOCUMENT_NUMBER", first.substring(5, 14).replace('<', ' ').trim(), 0.95f),
            field("NATIONALITY", second.substring(15, 18), 0.95f),
            field("DATE_OF_BIRTH", second.substring(0, 6), 0.95f),
            field("SEX", second[7].takeUnless { it == '<' }?.toString(), 0.95f),
            field("EXPIRY", second.substring(8, 14), 0.95f),
        )
        return MrzResult("I", second.substring(15, 18), fields)
    }

    private fun fit(line: String): String = line.take(44).padEnd(44, '<')

    private fun validCheck(line: String, start: Int, endExclusive: Int): Boolean {
        val checkIndex = endExclusive
        if (checkIndex >= line.length || !line[checkIndex].isDigit()) return false
        return checksum(line.substring(start, endExclusive)) == line[checkIndex].digitToInt()
    }

    private fun checksum(value: String): Int = value.mapIndexed { index, char ->
        valueOf(char) * checkWeights[index % 3]
    }.sum() % 10

    private fun validCompositeCheck(line: String): Boolean {
        if (line.length <= 43 || !line[43].isDigit()) return false
        val composite = line.substring(0, 10) +
            line.substring(13, 20) +
            line.substring(21, 28) +
            line.substring(28, 43)
        return checksum(composite) == line[43].digitToInt()
    }

    private fun valueOf(char: Char): Int = when {
        char == '<' -> 0
        char.isDigit() -> char.digitToInt()
        else -> char - 'A' + 10
    }

    private fun field(name: String, value: String?, confidence: Float): ExtractedField? =
        value?.takeIf { it.isNotBlank() }?.let { ExtractedField(name, it, confidence) }
}
