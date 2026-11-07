package com.expensetracker.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background schedule. Android may defer periodic work (Doze, battery saver), so the SMS
 * receiver handles new alerts in real time and the periodic scan is only a safety net.
 */
@Singleton
class WorkScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val wm get() = WorkManager.getInstance(context)

    fun schedulePeriodic() {
        wm.enqueueUniquePeriodicWork(
            SMS_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SmsScanWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            EMAIL_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EmailSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            MAINTENANCE, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    fun runSmsScan(full: Boolean, months: Int? = null) {
        // Without an explicit window the worker uses the months chosen in settings.
        val input = listOfNotNull(SmsScanWorker.KEY_FULL to full, months?.let { SmsScanWorker.KEY_MONTHS to it })
        val request = OneTimeWorkRequestBuilder<SmsScanWorker>().setInputData(workDataOf(*input.toTypedArray())).build()
        wm.enqueueUniqueWork(SMS_SCAN, if (full) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun runEmailSync(full: Boolean) {
        val request = OneTimeWorkRequestBuilder<EmailSyncWorker>()
            .setInputData(workDataOf(EmailSyncWorker.KEY_FULL to full, EmailSyncWorker.KEY_MANUAL to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniqueWork(EMAIL_SYNC, ExistingWorkPolicy.REPLACE, request)
    }

    fun runMaintenance() {
        wm.enqueueUniqueWork(MAINTENANCE_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<MaintenanceWorker>().build())
    }

    fun observeSmsScan(): Flow<WorkInfo?> = wm.getWorkInfosForUniqueWorkFlow(SMS_SCAN).map { it.lastOrNull() }
    fun observeEmailSync(): Flow<WorkInfo?> = wm.getWorkInfosForUniqueWorkFlow(EMAIL_SYNC).map { it.lastOrNull() }

    companion object {
        const val SMS_SCAN = "sms_scan"
        const val SMS_PERIODIC = "sms_periodic"
        const val EMAIL_SYNC = "email_sync"
        const val EMAIL_PERIODIC = "email_periodic"
        const val MAINTENANCE = "maintenance"
        const val MAINTENANCE_NOW = "maintenance_now"
    }
}
