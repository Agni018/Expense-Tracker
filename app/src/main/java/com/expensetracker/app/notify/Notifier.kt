package com.expensetracker.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.expensetracker.app.MainActivity
import com.expensetracker.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CH_TRANSACTIONS, "New transactions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A transaction was detected from an SMS, notification or email"
            },
            NotificationChannel(CH_BUDGETS, "Budget alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "50%, 80% and 100% of a budget used"
            },
            NotificationChannel(CH_BILLS, "Bill reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Upcoming recurring payments"
            },
            NotificationChannel(CH_SYNC, "Imports and sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Results of SMS scans, email sync and imports"
            },
        ).forEach(manager::createNotificationChannel)
    }

    fun canPost(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun transaction(transactionId: Long, title: String, text: String) =
        post(CH_TRANSACTIONS, (transactionId % Int.MAX_VALUE).toInt(), title, text, openIntent(transactionId))

    fun budget(key: Int, title: String, text: String) = post(CH_BUDGETS, 100_000 + key, title, text, openIntent(null, "budgets"))

    fun bill(key: Int, title: String, text: String) = post(CH_BILLS, 200_000 + key, title, text, openIntent(null, "budgets"))

    fun sync(title: String, text: String) = post(CH_SYNC, 300_000, title, text, openIntent(null, null))

    private fun post(channel: String, id: Int, title: String, text: String, intent: PendingIntent) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call; nothing else to do.
        }
    }

    private fun openIntent(transactionId: Long?, destination: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            transactionId?.let { putExtra(MainActivity.EXTRA_TRANSACTION_ID, it) }
            destination?.let { putExtra(MainActivity.EXTRA_DESTINATION, it) }
        }
        val request = (transactionId ?: destination.hashCode().toLong()).toInt()
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CH_TRANSACTIONS = "transactions"
        const val CH_BUDGETS = "budgets"
        const val CH_BILLS = "bills"
        const val CH_SYNC = "sync"
    }
}
