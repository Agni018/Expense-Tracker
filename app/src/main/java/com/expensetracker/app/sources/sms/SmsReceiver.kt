package com.expensetracker.app.sources.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.di.ApplicationScope
import com.expensetracker.app.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Processes bank alerts as they arrive. Work is short (parse + one DB transaction) so it runs
 * under goAsync(); on failure an incremental inbox scan is scheduled to catch up.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject lateinit var importer: SmsImporter
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var scheduler: WorkScheduler
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Long messages arrive as several parts from the same sender.
        val grouped = messages.filterNotNull().groupBy { it.originatingAddress.orEmpty() }
        val pending = goAsync()
        val receivedAt = System.currentTimeMillis()
        scope.launch {
            try {
                if (!settings.current().smsAutoImport) return@launch
                grouped.forEach { (address, parts) ->
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    importer.processIncoming(address, body, receivedAt)
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Failed to process incoming SMS", e)
                scheduler.runSmsScan(full = false)
            } finally {
                pending.finish()
            }
        }
    }
}
