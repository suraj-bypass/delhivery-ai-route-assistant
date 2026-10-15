package com.routeassistant.app.domain.parser

/**
 * Turns raw ML Kit OCR text into candidate addresses.
 *
 * Strategy (conservative - user confirms before anything is saved):
 * - Split into lines, trim, drop junk (time, battery %, pure numbers, UI chrome).
 * - Merge very short fragments with the previous line (OCR often splits
 *   "Flat 12, Building A" across two lines).
 * - Dedupe case-insensitively.
 * The UI shows every candidate with a checkbox + editable text, so a
 * bad merge can be fixed by the user before saving.
 */
object OcrTextParser {

    private val junkPatterns = listOf(
        Regex("^\\d{1,2}:\\d{2}(\\s?[AP]M)?$", RegexOption.IGNORE_CASE),
        Regex("^\\d{1,3}%$"),
        Regex("^(delhivery|track|home|profile|scan|search)$", RegexOption.IGNORE_CASE),
        Regex("^\\d+$"), // bare number
        Regex("^[\\W_]+$") // only punctuation
    )

    private fun isJunk(line: String): Boolean {
        val t = line.trim()
        if (t.length < 4) return true
        return junkPatterns.any { it.matches(t) }
    }

    fun parseCandidates(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        val lines = rawText.split("\n")
            .map { AddressParser.normalize(it) }
            .filter { it.isNotBlank() && !isJunk(it) }

        val merged = mutableListOf<String>()
        for (line in lines) {
            if (merged.isNotEmpty() && line.length < 18 && !line.contains(",") && !line.matches(Regex(".*\\d.*"))) {
                // Short fragment without a number/comma - likely a continuation.
                merged[merged.lastIndex] = "${merged.last()} $line"
            } else {
                merged.add(line)
            }
        }
        // Dedupe, keep order.
        val seen = mutableSetOf<String>()
        return merged.filter { seen.add(it.lowercase()) }
    }
}
