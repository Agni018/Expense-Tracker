package com.expensetracker.app.sources.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.expensetracker.app.core.Hashing
import com.expensetracker.app.data.ingest.PostIngest
import com.expensetracker.app.data.ingest.TransactionIngestor
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.parser.MessageParseResult
import com.expensetracker.app.domain.parser.NotificationParser
import com.expensetracker.app.sources.sms.SmsReader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Captures payment notifications from UPI and banking apps. SMS-app notifications are only
 * used when the app cannot read SMS directly, so the same alert is not processed twice.
 */
@AndroidEntryPoint
class PaymentNotificationListener : NotificationListenerService() {

    @Inject lateinit var parser: NotificationParser
    @Inject lateinit var ingestor: TransactionIngestor
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var smsReader: SmsReader
    @Inject lateinit var postIngest: PostIngest

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = parser.appFor(sbn.packageName) ?: return
        if (app.kind == NotificationParser.AppKind.SMS && smsReader.hasReadPermission()) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        val postedAt = sbn.postTime
        val packageName = sbn.packageName
        scope.launch {
            try {
                if (!settings.current().notificationCapture) return@launch
                // Apps re-post the same notification when it updates; bucket by 10 minutes.
                val hash = Hashing.sha256(packageName, title, text, postedAt / 600_000)
                if (ingestor.isProcessed(SourceType.NOTIFICATION, hash)) return@launch
                val raw = listOfNotNull(title, text).joinToString("\n")
                val item = TransactionIngestor.SourceItem(SourceType.NOTIFICATION, hash, app.name, raw, postedAt)
                when (val r = parser.parse(packageName, title, text, postedAt)) {
                    is MessageParseResult.Transaction -> postIngest.onRealtime(ingestor.ingest(item, r.txn))
                    is MessageParseResult.BalanceAlert -> ingestor.updateReportedBalance(r.accountLast4, r.balanceMinor, postedAt)
                    is MessageParseResult.Unparsed -> ingestor.recordUnparsed(item, r.reason, r.amountMinor)
                    is MessageParseResult.Ignored -> Unit
                }
            } catch (e: Exception) {
                Log.e("NotificationListener", "Failed to process notification from $packageName", e)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, PaymentNotificationListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
