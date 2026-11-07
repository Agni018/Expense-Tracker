package com.expensetracker.app.sources.statement

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.expensetracker.app.core.Hashing
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.ingest.PostIngest
import com.expensetracker.app.data.ingest.TransactionIngestor
import com.expensetracker.app.data.ingest.TransactionIngestor.Outcome
import com.expensetracker.app.data.repo.ImportRepository
import com.expensetracker.app.domain.engine.DuplicateDetector
import com.expensetracker.app.domain.model.AccountType
import com.expensetracker.app.domain.model.ImportKind
import com.expensetracker.app.domain.model.ImportStatus
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.parser.HtmlText
import com.expensetracker.app.domain.parser.ParsedTransaction
import com.expensetracker.app.domain.statement.ColumnMapping
import com.expensetracker.app.domain.statement.CsvReader
import com.expensetracker.app.domain.statement.NarrationParser
import com.expensetracker.app.domain.statement.StatementParseResult
import com.expensetracker.app.domain.statement.StatementRow
import com.expensetracker.app.domain.statement.TabularStatementParser
import com.expensetracker.app.domain.statement.TextStatementParser
import com.expensetracker.app.domain.statement.XlsxReader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-based statement import: CSV, XLSX, PDF (text or scanned, optionally password protected),
 * images (OCR) and Google Pay activity HTML. The file is copied into app storage so the import
 * can be reprocessed later from the import history.
 */
@Singleton
class StatementImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ingestor: TransactionIngestor,
    private val imports: ImportRepository,
    private val postIngest: PostIngest,
    private val ocr: OcrEngine,
    private val zone: ZoneId,
) {
    enum class FileKind(val sourceType: SourceType, val importKind: ImportKind) {
        CSV(SourceType.STATEMENT_CSV, ImportKind.STATEMENT_CSV),
        XLSX(SourceType.STATEMENT_XLSX, ImportKind.STATEMENT_XLSX),
        PDF(SourceType.STATEMENT_PDF, ImportKind.STATEMENT_PDF),
        IMAGE(SourceType.STATEMENT_IMAGE, ImportKind.STATEMENT_IMAGE),
        HTML(SourceType.STATEMENT_HTML, ImportKind.GPAY_ACTIVITY),
    }

    data class StoredFile(val path: String, val displayName: String, val mimeType: String?, val kind: FileKind)

    sealed interface Loaded {
        data class Tabular(val rows: List<List<String>>) : Loaded
        data class Text(val lines: List<String>, val usedOcr: Boolean) : Loaded
        data class NeedsPassword(val wrongPassword: Boolean) : Loaded
        data class Error(val message: String) : Loaded
    }

    data class PreviewRow(
        val row: StatementRow,
        val parsed: ParsedTransaction,
        val contentHash: String,
        val alreadyImported: Boolean,
        val duplicateOf: Long?,
        val duplicateReason: String?,
    )

    data class Summary(val imported: Int, val merged: Int, val alreadyImported: Int, val failed: Int, val skipped: Int, val status: ImportStatus)

    suspend fun store(uri: Uri): StoredFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "statement"
        val kind = kindOf(name, mime) ?: throw IllegalArgumentException("Unsupported file type. Use PDF, CSV, XLSX, an image, or Google Pay activity (HTML).")
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val target = File(dir, "${System.currentTimeMillis()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalArgumentException("Could not read the selected file")
        StoredFile(target.absolutePath, name, mime, kind)
    }

    fun storedFromPath(path: String, name: String, mime: String?): StoredFile? =
        kindOf(name, mime)?.let { StoredFile(path, name, mime, it) }

    private fun kindOf(name: String, mime: String?): FileKind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "csv" || ext == "txt" || mime == "text/csv" || mime == "text/comma-separated-values" -> FileKind.CSV
            ext == "xlsx" || mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> FileKind.XLSX
            ext == "pdf" || mime == "application/pdf" -> FileKind.PDF
            ext in setOf("jpg", "jpeg", "png", "webp", "heic") || mime?.startsWith("image/") == true -> FileKind.IMAGE
            ext == "html" || ext == "htm" || mime == "text/html" -> FileKind.HTML
            else -> null // includes legacy .xls, which must be re-saved as .xlsx or .csv
        }
    }

    suspend fun load(file: StoredFile, password: String?): Loaded = withContext(Dispatchers.IO) {
        try {
            val f = File(file.path)
            when (file.kind) {
                FileKind.CSV -> Loaded.Tabular(CsvReader.parse(f.readText()))
                FileKind.XLSX -> Loaded.Tabular(f.inputStream().use { XlsxReader.read(it) })
                FileKind.HTML -> Loaded.Text(HtmlText.toText(f.readText()).lines(), usedOcr = false)
                FileKind.IMAGE -> Loaded.Text(ocr.lines(Uri.fromFile(f)), usedOcr = true)
                FileKind.PDF -> loadPdf(f, password)
            }
        } catch (e: XlsxReader.XlsxException) {
            Loaded.Error(e.message ?: "Unreadable spreadsheet")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ${file.displayName}", e)
            Loaded.Error("Could not read ${file.displayName}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun loadPdf(f: File, password: String?): Loaded {
        val doc = try {
            PDDocument.load(f, password.orEmpty())
        } catch (e: InvalidPasswordException) {
            return Loaded.NeedsPassword(wrongPassword = !password.isNullOrEmpty())
        }
        doc.use {
            val text = PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            if (text.count { c -> c.isLetterOrDigit() } >= 80) return Loaded.Text(text.lines(), usedOcr = false)
            // Scanned PDF: render pages and run OCR.
            val renderer = PDFRenderer(doc)
            val lines = ArrayList<String>()
            for (page in 0 until minOf(doc.numberOfPages, MAX_OCR_PAGES)) {
                val bitmap = renderer.renderImageWithDPI(page, 200f)
                try {
                    lines += ocr.lines(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
            return Loaded.Text(lines, usedOcr = true)
        }
    }

    fun parse(loaded: Loaded, mapping: ColumnMapping?): StatementParseResult? {
        val today = LocalDate.now(zone)
        return when (loaded) {
            is Loaded.Tabular -> TabularStatementParser.parse(loaded.rows, mapping, today)
            is Loaded.Text -> TextStatementParser.parse(loaded.lines, today)
            else -> null
        }
    }

    suspend fun preview(file: StoredFile, result: StatementParseResult, account: AccountEntity?): List<PreviewRow> {
        val isCc = result.isCreditCardStatement || account?.type == AccountType.CREDIT_CARD
        val seen = HashMap<String, Int>()
        return result.rows.map { row ->
            val parsed = NarrationParser.toParsed(row, isCc, account?.last4, account?.institution, zone, file.kind.sourceType.label)
            // Identical rows (two ₹20 teas on one day) stay distinct through their occurrence index.
            val base = Hashing.sha256(account?.id, row.date, row.description, row.amountMinor, row.direction, row.balanceMinor)
            val occurrence = seen.merge(base, 1, Int::plus)!!
            val hash = Hashing.sha256(base, occurrence)
            val item = TransactionIngestor.SourceItem(file.kind.sourceType, hash, file.displayName, row.raw, parsed.timestamp, accountId = account?.id)
            val already = ingestor.isProcessed(file.kind.sourceType, hash)
            val dup = if (already) null else ingestor.findDuplicate(item, parsed, account?.last4)
            PreviewRow(
                row, parsed, hash, already,
                dup?.takeIf { it.second.verdict == DuplicateDetector.Verdict.DUPLICATE }?.first?.id,
                dup?.second?.reasons?.joinToString("; "),
            )
        }
    }

    suspend fun commit(
        file: StoredFile,
        rows: List<PreviewRow>,
        selected: Set<Int>,
        account: AccountEntity?,
        failedLines: Int,
        existingImportId: Long? = null,
    ): Summary {
        val importId = existingImportId ?: imports.start(file.kind.importKind, file.displayName, file.path, file.mimeType, account?.id)
        var imported = 0
        var merged = 0
        var already = 0
        rows.forEachIndexed { index, r ->
            if (index !in selected) return@forEachIndexed
            val item = TransactionIngestor.SourceItem(
                file.kind.sourceType, r.contentHash, file.displayName, r.row.raw, r.parsed.timestamp, importId, account?.id,
            )
            when (ingestor.ingest(item, r.parsed)) {
                is Outcome.Created, is Outcome.FlaggedDuplicate -> imported++
                is Outcome.Merged -> merged++
                Outcome.AlreadyProcessed -> already++
            }
        }
        val skipped = rows.size - selected.size
        val status = imports.finish(
            importId,
            ImportRepository.Counts(total = rows.size + failedLines, imported = imported, duplicates = merged + already, failed = failedLines, skipped = skipped),
        )
        if (imported > 0) postIngest.checkBudgets()
        return Summary(imported, merged, already, failedLines, skipped, status)
    }

    /** Records a failed attempt so it shows in the import history (never marked successful). */
    suspend fun recordFailure(file: StoredFile, message: String, accountId: Long?) {
        val id = imports.start(file.kind.importKind, file.displayName, file.path, file.mimeType, accountId)
        imports.finish(id, ImportRepository.Counts(), error = message)
    }

    companion object {
        private const val TAG = "StatementImporter"
        private const val MAX_OCR_PAGES = 20
    }
}
