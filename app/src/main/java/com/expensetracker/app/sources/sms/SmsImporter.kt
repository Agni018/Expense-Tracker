package com.expensetracker.app.sources.sms

import android.util.Log
import com.expensetracker.app.core.Hashing
import com.expensetracker.app.data.ingest.PostIngest
import com.expensetracker.app.data.ingest.TransactionIngestor
import com.expensetracker.app.data.ingest.TransactionIngestor.Outcome
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.data.repo.ImportRepository
import com.expensetracker.app.domain.model.ImportKind
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.parser.MessageParseResult
import com.expensetracker.app.domain.parser.SmsParser
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsImporter @Inject constructor(
    private val reader: SmsReader,
    private val parser: SmsParser,
    private val ingestor: TransactionIngestor,
    private val settings: SettingsRepository,
    private val imports: ImportRepository,
    private val postIngest: PostIngest,
    private val zone: ZoneId,
) {
    data class ScanResult(
        val scanned: Int = 0,
        val created: Int = 0,
        val merged: Int = 0,
        val review: Int = 0,
        val ignored: Int = 0,
        val unparsed: Int = 0,
        val alreadyProcessed: Int = 0,
    ) {
        val financial: Int get() = created + merged + unparsed
    }

    /** Handles one freshly delivered SMS (from the broadcast receiver). */
    suspend fun processIncoming(address: String, body: String, timestamp: Long) {
        val outcome = processOne(SmsReader.Sms(-1, address, body, timestamp), importId = null)
        if (outcome != null) postIngest.onRealtime(outcome)
    }

    private suspend fun processOne(sms: SmsReader.Sms, importId: Long?, counter: ((ScanResult) -> ScanResult) -> Unit = {}): Outcome? {
        val hash = Hashing.sha256(sms.address, sms.body, sms.date)
        val item = TransactionIngestor.SourceItem(SourceType.SMS, hash, sms.address, sms.body, sms.date, importId)
        if (ingestor.isProcessed(SourceType.SMS, hash) || ingestor.isBodyProcessed(item)) {
            counter { it.copy(alreadyProcessed = it.alreadyProcessed + 1) }
            return Outcome.AlreadyProcessed
        }
        return when (val r = parser.parse(sms.address, sms.body, sms.date)) {
            is MessageParseResult.Transaction -> {
                val outcome = ingestor.ingest(item, r.txn)
                counter {
                    when (outcome) {
                        is Outcome.Created -> it.copy(created = it.created + 1, review = it.review + if (outcome.needsReview) 1 else 0)
                        is Outcome.FlaggedDuplicate -> it.copy(created = it.created + 1, review = it.review + 1)
                        is Outcome.Merged -> it.copy(merged = it.merged + 1)
                        Outcome.AlreadyProcessed -> it.copy(alreadyProcessed = it.alreadyProcessed + 1)
                    }
                }
                outcome
            }
            is MessageParseResult.BalanceAlert -> {
                ingestor.updateReportedBalance(r.accountLast4, r.balanceMinor, sms.date)
                counter { it.copy(ignored = it.ignored + 1) }
                null
            }
            is MessageParseResult.Unparsed -> {
                ingestor.recordUnparsed(item, r.reason, r.amountMinor)
                counter { it.copy(unparsed = it.unparsed + 1) }
                null
            }
            is MessageParseResult.Ignored -> {
                counter { it.copy(ignored = it.ignored + 1) }
                null
            }
        }
    }

    /**
     * Scans the inbox. [full] rescans the last [months] months (already processed messages are
     * skipped by their hash); otherwise only messages newer than the last scan are read.
     */
    suspend fun scan(full: Boolean, months: Int, onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): ScanResult {
        val s = settings.current()
        val windowStart = ZonedDateTime.now(zone).minusMonths(months.toLong()).toInstant().toEpochMilli()
        val since = if (full) windowStart else maxOf(s.smsCursor, windowStart)
        val total = reader.count(since)
        val importId = if (full || total > 0) imports.start(ImportKind.SMS_SCAN, if (full) "Full scan ($months months)" else "New messages") else null
        var result = ScanResult()
        var latest = since
        var done = 0
        try {
            reader.forEachSince(since) { sms ->
                processOne(sms, importId) { update -> result = update(result) }
                latest = maxOf(latest, sms.date)
                done++
                if (done % 25 == 0 || done == total) onProgress(done, total)
            }
            settings.recordSmsScan(latest, System.currentTimeMillis())
            result = result.copy(scanned = done)
            importId?.let {
                imports.finish(
                    it,
                    ImportRepository.Counts(
                        total = done, imported = result.created, duplicates = result.merged + result.alreadyProcessed,
                        failed = result.unparsed, skipped = result.ignored,
                    ),
                )
            }
            if (result.created > 0) postIngest.checkBudgets()
        } catch (e: Exception) {
            Log.e(TAG, "SMS scan failed", e)
            importId?.let { imports.finish(it, ImportRepository.Counts(total = done, imported = result.created), error = e.message ?: e.javaClass.simpleName) }
            throw e
        }
        return result
    }

    companion object {
        private const val TAG = "SmsImporter"
    }
}
