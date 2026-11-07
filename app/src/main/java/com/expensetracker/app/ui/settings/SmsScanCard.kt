package com.expensetracker.app.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.sources.sms.SmsReader
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.work.SmsScanWorker
import com.expensetracker.app.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SmsScanViewModel @Inject constructor(
    private val scheduler: WorkScheduler,
    private val reader: SmsReader,
    private val settings: SettingsRepository,
) : ViewModel() {
    val work: StateFlow<WorkInfo?> = scheduler.observeSmsScan().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val appSettings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _permission = MutableStateFlow(reader.hasReadPermission())
    val permission: StateFlow<Boolean> = _permission

    fun refreshPermission() {
        _permission.value = reader.hasReadPermission()
    }

    fun scan(months: Int) = viewModelScope.launch {
        settings.setSmsScanMonths(months)
        scheduler.runSmsScan(full = true, months = months)
    }
}

/** Permission request, scan window choice, live progress and the last result. */
@Composable
fun SmsScanCard(vm: SmsScanViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val work by vm.work.collectAsStateWithLifecycle()
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    val permission by vm.permission.collectAsStateWithLifecycle()
    var months by remember(settings?.smsScanMonths) { mutableStateOf(settings?.smsScanMonths ?: 6) }
    var denied by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermission() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.refreshPermission()
        denied = result[Manifest.permission.READ_SMS] != true
        if (!denied) vm.scan(months)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!permission) {
            Text(
                "SMS access lets the app read bank and UPI alerts on this phone. Messages are processed on the device; nothing is uploaded.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = {
                val perms = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(perms.toTypedArray())
            }) { Text("Allow SMS access") }
            if (denied) {
                Text(
                    "Permission was not granted. On Android 13 and later, apps installed from an APK may have SMS access blocked: open App info → ⋮ (top right) → \"Allow restricted settings\", then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Open app info") }
            }
            return@Column
        }
        Text("Scan messages from the last", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 3, 6, 12, 24).forEach { m ->
                FilterChip(selected = months == m, onClick = { months = m }, label = { Text(if (m < 12) "$m mo" else "${m / 12} yr") })
            }
        }
        val running = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED
        if (running) {
            val done = work?.progress?.getInt(SmsScanWorker.KEY_DONE, 0) ?: 0
            val total = work?.progress?.getInt(SmsScanWorker.KEY_TOTAL, 0) ?: 0
            if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (total > 0) "Reading messages… $done of $total" else "Starting scan…", style = MaterialTheme.typography.bodySmall)
        } else {
            // Already processed messages are skipped, so scanning again only adds what is new.
            Button(onClick = { vm.scan(months) }) { Text("Scan SMS now") }
        }
        val w = work
        if (w?.state == WorkInfo.State.SUCCEEDED) {
            val created = w.outputData.getInt(SmsScanWorker.KEY_CREATED, 0)
            val scanned = w.outputData.getInt(SmsScanWorker.KEY_SCANNED, 0)
            val merged = w.outputData.getInt(SmsScanWorker.KEY_MERGED, 0)
            val review = w.outputData.getInt(SmsScanWorker.KEY_REVIEW, 0)
            val unparsed = w.outputData.getInt(SmsScanWorker.KEY_UNPARSED, 0)
            if (scanned > 0 || created > 0) {
                Text(
                    "Last scan: $scanned messages read · $created new transactions · $merged already recorded" +
                        (if (review > 0) " · $review to review" else "") + (if (unparsed > 0) " · $unparsed unrecognised" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (w?.state == WorkInfo.State.FAILED) {
            Text("Scan failed: ${w.outputData.getString(SmsScanWorker.KEY_ERROR) ?: "unknown error"}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        settings?.let {
            Spacer(Modifier.height(2.dp))
            Text("Last processed ${Fmt.relative(it.lastSmsScanAt)} · new alerts are recorded as they arrive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
