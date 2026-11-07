package com.expensetracker.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.expensetracker.app.sources.sms.SmsReader
import com.expensetracker.app.ui.AppRoot
import com.expensetracker.app.ui.LaunchRequest
import com.expensetracker.app.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var scheduler: WorkScheduler
    @Inject lateinit var smsReader: SmsReader

    private val launchRequests = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppRoot(launchRequests = launchRequests, onLaunchHandled = { launchRequests.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Catch up on SMS that arrived while the receiver could not run (KEEP: no duplicate work).
        if (smsReader.hasReadPermission()) scheduler.runSmsScan(full = false)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val request = when {
            intent.hasExtra(EXTRA_TRANSACTION_ID) -> LaunchRequest.OpenTransaction(intent.getLongExtra(EXTRA_TRANSACTION_ID, 0))
            intent.hasExtra(EXTRA_DESTINATION) -> LaunchRequest.Open(intent.getStringExtra(EXTRA_DESTINATION).orEmpty())
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" && intent.getParcelableStream() == null ->
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { LaunchRequest.SharedText(it) }
            intent.action == Intent.ACTION_SEND -> intent.getParcelableStream()?.let { LaunchRequest.SharedFile(it, intent.type) }
            intent.action == Intent.ACTION_VIEW -> intent.data?.let { LaunchRequest.SharedFile(it, intent.type) }
            else -> null
        }
        if (request != null) launchRequests.value = request
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableStream(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

    companion object {
        const val EXTRA_TRANSACTION_ID = "transaction_id"
        const val EXTRA_DESTINATION = "destination"
    }
}
