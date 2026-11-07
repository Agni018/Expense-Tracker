package com.expensetracker.app.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.expensetracker.app.core.Money
import com.expensetracker.app.data.ingest.PostIngest
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.data.repo.RecurringRepository
import com.expensetracker.app.domain.model.RecurringStatus
import com.expensetracker.app.notify.Notifier
import com.expensetracker.app.sources.email.EmailImporter
import com.expensetracker.app.sources.sms.SmsImporter
import com.expensetracker.app.sources.sms.SmsReader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@HiltWorker
class SmsScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val importer: SmsImporter,
    private val reader: SmsReader,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!reader.hasReadPermission()) return Result.success(workDataOf(KEY_ERROR to "SMS permission not granted"))
        val full = inputData.getBoolean(KEY_FULL, false)
        val months = inputData.getInt(KEY_MONTHS, settings.current().smsScanMonths)
        return try {
            val r = importer.scan(full, months) { done, total -> setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total)) }
            if (full) notifier.sync("SMS scan complete", "${r.created} new transactions from ${r.scanned} messages (${r.merged} duplicates merged).")
            Result.success(
                workDataOf(KEY_SCANNED to r.scanned, KEY_CREATED to r.created, KEY_MERGED to r.merged, KEY_REVIEW to r.review, KEY_UNPARSED to r.unparsed),
            )
        } catch (e: Exception) {
            Log.e("SmsScanWorker", "Scan failed", e)
            if (runAttemptCount < 2) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Scan failed")))
        }
    }

    companion object {
        const val KEY_FULL = "full"
        const val KEY_MONTHS = "months"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_SCANNED = "scanned"
        const val KEY_CREATED = "created"
        const val KEY_MERGED = "merged"
        const val KEY_REVIEW = "review"
        const val KEY_UNPARSED = "unparsed"
        const val KEY_ERROR = "error"
    }
}

@HiltWorker
class EmailSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val importer: EmailImporter,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val s = settings.current()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!s.emailConfigured || (!manual && !s.emailAutoSync)) return Result.success()
        val r = importer.sync(full = inputData.getBoolean(KEY_FULL, false))
        return when {
            r.error == null -> Result.success(workDataOf(KEY_CREATED to r.created, KEY_CHECKED to r.checked))
            // Login problems need the user; network problems are retried.
            r.error.contains("Login failed") -> Result.failure(workDataOf(KEY_ERROR to r.error))
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure(workDataOf(KEY_ERROR to r.error))
        }
    }

    companion object {
        const val KEY_FULL = "full"
        const val KEY_MANUAL = "manual"
        const val KEY_CREATED = "created"
        const val KEY_CHECKED = "checked"
        const val KEY_ERROR = "error"
    }
}

/** Daily: recurring payment detection, bill reminders and budget alerts. */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val recurring: RecurringRepository,
    private val postIngest: PostIngest,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val zone: ZoneId,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        recurring.refresh()
        if (settings.current().billReminders) {
            val now = System.currentTimeMillis()
            val horizon = now + 2 * 86_400_000L
            val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
            recurring.all()
                .filter { it.reminderEnabled && it.status != RecurringStatus.DISMISSED && (it.status == RecurringStatus.CONFIRMED || it.isSubscription) }
                .filter { it.nextDueAt in now..horizon && it.lastReminderFor != it.nextDueAt }
                .forEach {
                    val due = Instant.ofEpochMilli(it.nextDueAt).atZone(zone).format(fmt)
                    notifier.bill(it.id.toInt(), "${it.displayName} expected $due", "Usually ${Money.format(it.expectedAmountMinor)}. This is a prediction; nothing has been paid yet.")
                    recurring.markReminded(it)
                }
        }
        postIngest.checkBudgets()
        return Result.success()
    }
}
