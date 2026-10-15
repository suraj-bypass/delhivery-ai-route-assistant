package com.routeassistant.app.domain.parser

/**
 * Bulk-paste parsing: split by newline, normalise, dedupe.
 * Duplicates are reported, never silently dropped.
 */
object AddressParser {

    data class ParsedBulkResult(
        val addresses: List<String>,
        /** Addresses that were duplicates (normalised) within the paste or vs existing. */
        val duplicates: List<String>,
        val droppedEmptyLines: Int
    )

    /** Normalisation used for dedupe + geocoding: collapse whitespace, trim, lowercase for compare. */
    fun normalize(address: String): String =
        address.trim().replace(Regex("\\s+"), " ")

    fun normalizeForCompare(address: String): String = normalize(address).lowercase()

    /**
     * Parse bulk text. [existingNormalized] = already-stored parcels (compare-normalised)
     * so pasting an existing address flags it as duplicate instead of creating a second row.
     */
    fun parseBulk(
        text: String,
        existingNormalized: Set<String> = emptySet()
    ): ParsedBulkResult {
        val seen = existingNormalized.toMutableSet()
        val addresses = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        var dropped = 0
        for (rawLine in text.split("\n")) {
            val line = normalize(rawLine)
            if (line.isBlank()) { dropped++; continue }
            if (line.length < 5) {
                // Too short to be a deliverable address - treat as junk, count as dropped.
                dropped++
                continue
            }
            val key = line.lowercase()
            if (key in seen) {
                duplicates.add(line)
            } else {
                seen.add(key)
                addresses.add(line)
            }
        }
        return ParsedBulkResult(addresses, duplicates, dropped)
    }
}
