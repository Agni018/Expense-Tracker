package com.expensetracker.app.domain.statement

/** RFC 4180 style reader with delimiter detection (comma, semicolon, tab, pipe). */
object CsvReader {

    fun parse(text: String): List<List<String>> {
        val content = text.removePrefix("﻿")
        val delimiter = detectDelimiter(content)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                inQuotes && c == '"' && i + 1 < content.length && content[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' && (field.isEmpty() || inQuotes) -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> {
                    row.add(field.toString().trim())
                    field.setLength(0)
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < content.length && content[i + 1] == '\n') i++
                    row.add(field.toString().trim())
                    field.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString().trim())
            if (row.any { it.isNotEmpty() }) rows.add(row)
        }
        return rows
    }

    private fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(30).toList()
        val candidates = listOf(',', ';', '\t', '|')
        return candidates.maxByOrNull { d ->
            val counts = sample.map { line -> countOutsideQuotes(line, d) }
            // Prefer a delimiter that appears consistently across lines.
            counts.count { it > 0 } * 10 + (counts.maxOrNull() ?: 0)
        } ?: ','
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var inQuotes = false
        var count = 0
        for (c in line) {
            if (c == '"') inQuotes = !inQuotes else if (c == d && !inQuotes) count++
        }
        return count
    }
}
