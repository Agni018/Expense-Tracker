package com.expensetracker.app.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.expensetracker.app.BuildConfig
import com.expensetracker.app.data.prefs.AppSettings
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.data.prefs.ThemeMode
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.notify.Notifier
import com.expensetracker.app.sources.notification.PaymentNotificationListener
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.SettingsRow
import com.expensetracker.app.ui.components.SwitchRow
import com.expensetracker.app.ui.theme.Accents
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    transactions: TransactionRepository,
    private val notifier: Notifier,
) : ViewModel() {
    val state: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val reviewCount = transactions.observeReviewCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val unparsedCount = transactions.observeUnparsed().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val _canNotify = MutableStateFlow(notifier.canPost())
    val canNotify: StateFlow<Boolean> = _canNotify

    fun refresh() { _canNotify.value = notifier.canPost() }

    fun setName(v: String) = viewModelScope.launch { settings.setUserName(v) }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { settings.setThemeMode(v) }
    fun setAccent(i: Int) = viewModelScope.launch { settings.setAccent(i) }
    fun setDynamic(v: Boolean) = viewModelScope.launch { settings.setDynamicColor(v) }
    fun setSmsAuto(v: Boolean) = viewModelScope.launch { settings.setSmsAutoImport(v) }
    fun setNotificationCapture(v: Boolean) = viewModelScope.launch { settings.setNotificationCapture(v) }
    fun setNotifyNew(v: Boolean) = viewModelScope.launch { settings.setNotifyNew(v) }
    fun setBudgetAlerts(v: Boolean) = viewModelScope.launch { settings.setBudgetAlerts(v) }
    fun setBillReminders(v: Boolean) = viewModelScope.launch { settings.setBillReminders(v) }
}

@Composable
fun SettingsScreen(nav: NavController, padding: PaddingValues, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val review by vm.reviewCount.collectAsStateWithLifecycle()
    val unparsed by vm.unparsedCount.collectAsStateWithLifecycle()
    val canNotify by vm.canNotify.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var listenerEnabled by remember { mutableStateOf(PaymentNotificationListener.isEnabled(context)) }
    var editName by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        listenerEnabled = PaymentNotificationListener.isEnabled(context)
        vm.refresh()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val settings = s ?: return

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }

        item {
            SectionCard(title = "Automatic tracking from SMS") {
                SmsScanCard()
                Spacer(Modifier.height(8.dp))
                SwitchRow(Icons.Rounded.Sms, "Record new bank SMS automatically", "Processes alerts as soon as they arrive", settings.smsAutoImport, vm::setSmsAuto)
            }
        }

        item {
            SectionCard(title = "More ways to capture") {
                SettingsRow(
                    Icons.Rounded.NotificationsActive,
                    "Payment app notifications",
                    if (listenerEnabled) "On — Google Pay, PhonePe, Paytm, BHIM and bank apps" else "Off — tap to allow notification access",
                    onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                )
                if (listenerEnabled) {
                    SwitchRow(Icons.Rounded.Tune, "Use payment notifications", "Turn off to pause without removing access", settings.notificationCapture, vm::setNotificationCapture)
                }
                SettingsRow(
                    Icons.Rounded.Email, "Email receipts and alerts",
                    if (settings.emailConfigured) "${settings.emailAddress} · synced ${Fmt.relative(settings.emailLastSyncAt)}" + (if (settings.emailLastError.isNotBlank()) " · error" else "") else "Connect Gmail/Outlook with IMAP, or import .eml files",
                    onClick = { nav.navigate(Routes.EMAIL) },
                )
                SettingsRow(Icons.Rounded.Description, "Import bank statement", "PDF (incl. password protected), CSV, Excel, scanned image, PhonePe or Google Pay export", onClick = { nav.navigate(Routes.statement()) })
                SettingsRow(Icons.Rounded.CameraAlt, "Scan a receipt", "Read merchant, amount and date with on-device OCR", onClick = { nav.navigate(Routes.receipt()) })
                SettingsRow(Icons.Rounded.Science, "Test the SMS parser", "Paste any message to see what would be recorded", onClick = { nav.navigate(Routes.parser()) })
            }
        }

        item {
            SectionCard(title = "Review & history") {
                SettingsRow(
                    Icons.Rounded.Warning, "Review queue",
                    when {
                        review > 0 || unparsed > 0 -> "$review to review · $unparsed unrecognised messages"
                        else -> "Nothing to review"
                    },
                    onClick = { nav.navigate(Routes.REVIEW) },
                )
                SettingsRow(Icons.Rounded.History, "Import & scan history", "Every SMS scan, email sync and statement import", onClick = { nav.navigate(Routes.IMPORTS) })
            }
        }

        item {
            SectionCard(title = "Organise") {
                SettingsRow(Icons.Rounded.AccountBalance, "Accounts", "Bank accounts, cards, cash and wallets", onClick = { nav.navigate(Routes.ACCOUNTS) })
                SettingsRow(Icons.Rounded.Category, "Categories", "Add, rename or recolour categories", onClick = { nav.navigate(Routes.CATEGORIES) })
                SettingsRow(Icons.Rounded.Tune, "Categorisation rules", "Your rules and ones learned from your edits", onClick = { nav.navigate(Routes.RULES) })
            }
        }

        item {
            SectionCard(title = "Notifications") {
                if (!canNotify) {
                    SettingsRow(Icons.Rounded.Notifications, "Allow notifications", "Needed for budget alerts and bill reminders", onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    })
                }
                SwitchRow(Icons.AutoMirrored.Rounded.ReceiptLong, "New transaction alerts", "When an SMS or notification is recorded", settings.notifyNewTransactions, vm::setNotifyNew)
                SwitchRow(Icons.Rounded.Warning, "Budget alerts", "At 50%, 80%, 100% and when exceeded", settings.budgetAlerts, vm::setBudgetAlerts)
                SwitchRow(Icons.Rounded.Repeat, "Bill reminders", "Two days before an expected recurring payment", settings.billReminders, vm::setBillReminders)
            }
        }

        item {
            SectionCard(title = "Appearance") {
                SettingsRow(Icons.Rounded.Person, "Your name", settings.userName.ifBlank { "Used in the greeting" }, onClick = { editName = true })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(selected = settings.themeMode == mode, onClick = { vm.setTheme(mode) }, shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) { Text(mode.label) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Palette, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Accents.forEachIndexed { i, accent ->
                            val selected = settings.accentIndex == i && !settings.dynamicColor
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(accent.primary)
                                    .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                    .clickable { vm.setAccent(i); vm.setDynamic(false) },
                                contentAlignment = Alignment.Center,
                            ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(Icons.Rounded.Palette, "Use wallpaper colours", "Material You dynamic colour", settings.dynamicColor, vm::setDynamic)
                }
            }
        }

        item {
            SectionCard(title = "Data") {
                SettingsRow(Icons.Rounded.Backup, "Backup, restore & reports", "Export JSON backups, CSV and PDF reports", onClick = { nav.navigate(Routes.BACKUP) })
                SettingsRow(Icons.Rounded.QuestionAnswer, "Ask about your spending", "Answers questions using the data on this phone", onClick = { nav.navigate(Routes.ASSISTANT) })
                SettingsRow(Icons.Rounded.Lock, "Privacy", "All data stays on this device. Nothing is uploaded; email sync talks only to your mail server.")
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Expense Tracker ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (editName) {
        var name by remember { mutableStateOf(settings.userName) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text("Your name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { vm.setName(name); editName = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Cancel") } },
        )
    }
}
