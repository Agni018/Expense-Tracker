package com.expensetracker.app.domain.statement

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Minimal XLSX reader (first worksheet only). An .xlsx file is a zip of XML parts, so this
 * avoids shipping Apache POI. Legacy binary .xls files are not supported.
 */
object XlsxReader {

    class XlsxException(message: String) : Exception(message)

    private const val MAX_PART_BYTES = 60L * 1024 * 1024

    fun read(input: InputStream): List<List<String>> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name.endsWith(".xml") || name.endsWith(".rels")) {
                    val bytes = zip.readBytes()
                    if (bytes.size > MAX_PART_BYTES) throw XlsxException("Spreadsheet part $name is too large")
                    parts[name] = bytes
                }
            }
        }
        if (parts.isEmpty()) throw XlsxException("Not an .xlsx file (old .xls files must be re-saved as .xlsx or .csv)")
        val shared = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val dateStyles = parts["xl/styles.xml"]?.let { dateStyleIndices(it) } ?: emptySet()
        val sheet = firstSheetPath(parts) ?: throw XlsxException("No worksheet found")
        val bytes = parts[sheet] ?: throw XlsxException("Worksheet $sheet missing")
        return sheetRows(bytes, shared, dateStyles)
    }

    private fun local(qName: String, localName: String?) = (localName?.takeIf { it.isNotEmpty() } ?: qName).substringAfter(':')

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        parse(bytes, object : DefaultHandler() {
            val current = StringBuilder()
            var inT = false
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                when (local(qName, localName)) {
                    "si" -> current.setLength(0)
                    "t" -> inT = true
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (local(qName, localName)) {
                    "t" -> inT = false
                    "si" -> result.add(current.toString())
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inT) current.appendRange(ch, start, start + length)
            }
        })
        return result
    }

    private val builtInDateFormats = (14..22).toSet() + setOf(27, 30, 36, 45, 46, 47, 50, 57)

    private fun dateStyleIndices(bytes: ByteArray): Set<Int> {
        val customDateFormats = HashSet<Int>()
        val styles = ArrayList<Int>()
        parse(bytes, object : DefaultHandler() {
            var inCellXfs = false
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (local(qName, localName)) {
                    "numFmt" -> {
                        val id = attributes.getValue("numFmtId")?.toIntOrNull()
                        val code = attributes.getValue("formatCode")?.lowercase().orEmpty()
                        val stripped = code.replace(Regex("\"[^\"]*\"|\\[[^]]*]"), "")
                        if (id != null && stripped.contains('d') && stripped.contains('y')) customDateFormats += id
                    }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) styles += attributes.getValue("numFmtId")?.toIntOrNull() ?: 0
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (local(qName, localName) == "cellXfs") inCellXfs = false
            }
        })
        return styles.withIndex().filter { it.value in builtInDateFormats || it.value in customDateFormats }.map { it.index }.toSet()
    }

    private fun firstSheetPath(parts: Map<String, ByteArray>): String? {
        val workbook = parts["xl/workbook.xml"] ?: return parts.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
        var firstRid: String? = null
        parse(workbook, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                if (local(qName, localName) == "sheet" && firstRid == null) {
                    firstRid = (0 until attributes.length).firstOrNull { attributes.getQName(it).endsWith(":id") || attributes.getLocalName(it) == "id" }
                        ?.let { attributes.getValue(it) }
                }
            }
        })
        val rels = parts["xl/_rels/workbook.xml.rels"]
        var target: String? = null
        if (rels != null && firstRid != null) {
            parse(rels, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    if (local(qName, localName) == "Relationship" && attributes.getValue("Id") == firstRid) {
                        target = attributes.getValue("Target")
                    }
                }
            })
        }
        val resolved = target?.let { t -> if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t.removePrefix("./") }
        return resolved?.takeIf { parts.containsKey(it) } ?: parts.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
    }

    private fun sheetRows(bytes: ByteArray, shared: List<String>, dateStyles: Set<Int>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        parse(bytes, object : DefaultHandler() {
            var row: MutableMap<Int, String>? = null
            var cellCol = 0
            var cellType: String? = null
            var cellStyle = 0
            val value = StringBuilder()
            var capture = false
            var nextCol = 0

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (local(qName, localName)) {
                    "row" -> {
                        row = HashMap()
                        nextCol = 0
                    }
                    "c" -> {
                        cellCol = attributes.getValue("r")?.let { columnIndex(it) } ?: nextCol
                        cellType = attributes.getValue("t")
                        cellStyle = attributes.getValue("s")?.toIntOrNull() ?: 0
                        value.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (local(qName, localName)) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (cellType) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            "inlineStr", "str" -> raw
                            else -> if (cellStyle in dateStyles) excelDate(raw) ?: raw else numberText(raw)
                        }
                        row?.put(cellCol, text.trim())
                        nextCol = cellCol + 1
                    }
                    "row" -> {
                        val r = row ?: return
                        if (r.values.any { it.isNotEmpty() }) {
                            val width = (r.keys.maxOrNull() ?: -1) + 1
                            rows.add(List(width) { r[it] ?: "" })
                        }
                        row = null
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) value.appendRange(ch, start, start + length)
            }
        })
        return rows
    }

    fun columnIndex(cellRef: String): Int {
        var index = 0
        for (ch in cellRef) {
            if (!ch.isLetter()) break
            index = index * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return index - 1
    }

    /** Excel serial date (1900 system) to ISO date. */
    fun excelDate(raw: String): String? {
        val serial = raw.toDoubleOrNull() ?: return null
        if (serial < 1 || serial > 2958465) return null
        return LocalDate.of(1899, 12, 30).plusDays(serial.toLong()).toString()
    }

    /** Avoids scientific notation and float noise (450.1 stored as 450.10000000000002). */
    private fun numberText(raw: String): String {
        val bd = raw.trim().toBigDecimalOrNull() ?: return raw
        val rounded = if (bd.scale() > 2) bd.setScale(2, java.math.RoundingMode.HALF_UP) else bd
        return rounded.stripTrailingZeros().toPlainString()
    }
}
