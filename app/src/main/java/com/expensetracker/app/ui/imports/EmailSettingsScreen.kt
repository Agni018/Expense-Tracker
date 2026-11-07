package com.expensetracker.app.ui.imports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.work.WorkInfo
import com.expensetracker.app.data.prefs.AppSettings
import com.expensetracker.app.data.prefs.SecretStore
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.sources.email.EmailImporter
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.SimpleTopBar
import com.expensetracker.app.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class EmailSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val importer: EmailImporter,
    private val scheduler: WorkScheduler,
) : ViewModel() {
    val current: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val work: StateFlow<WorkInfo?> = scheduler.observeEmailSync().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val busy = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 3)
    val attachments = MutableStateFlow<List<File>>(emptyList())

    fun defaultHost(address: String) = importer.defaultHost(address)

    fun testAndSave(address: String, host: String, port: Int, password: String, autoSync: Boolean, lookback: Int) = viewModelScope.launch {
        busy.value = true
        val existingPassword = current.value?.emailPasswordEnc?.takeIf { it.isNotBlank() }?.let { secrets.decrypt(it) }
        val pw = password.ifBlank { existingPassword.orEmpty() }
        val result = importer.testConnection(EmailImporter.Config(host, port, address, pw))
        busy.value = false
        result.onSuccess { count ->
            settings.saveEmailConfig(address, host, port, if (password.isNotBlank()) secrets.encrypt(password) else null, autoSync, lookback)
            messages.emit("Connected – $count messages in the inbox. Saved.")
        }.onFailure { e ->
            val text = e.message.orEmpty()
            messages.emit(
                if (text.contains("auth", true) || text.contains("credential", true)) "Login failed. Gmail needs an App Password (not your normal password)."
                else "Could not connect: ${text.ifBlank { e.javaClass.simpleName }}",
            )
        }
    }

    fun sync(full: Boolean) {
        scheduler.runEmailSync(full)
        viewModelScope.launch { messages.emit(if (full) "Rescanning the look-back window…" else "Sync started") }
    }

    fun disconnect() = viewModelScope.launch {
        settings.clearEmailConfig()
        messages.emit("Email disconnected")
    }

    fun importEml(uri: Uri) = viewModelScope.launch {
        busy.value = true
        val r = importer.importEml(uri, uri.lastPathSegment)
        busy.value = false
        attachments.value = r.attachments
        messages.emit(
            r.error ?: when {
                r.created > 0 -> "Transaction added from the email"
                r.merged > 0 -> "Matched a transaction that was already recorded"
                r.attachments.isNotEmpty() -> "No transaction in the email body; ${r.attachments.size} attachment(s) can be imported below"
                else -> "No transaction found in this email"
            },
        )
    }
}

@Composable
fun EmailSettingsScreen(nav: NavController, vm: EmailSettingsViewModel = hiltViewModel()) {
    val s by vm.current.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val work by vm.work.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val settings = s ?: return

    var address by remember(settings.emailAddress) { mutableStateOf(settings.emailAddress) }
    var host by remember(settings.emailHost) { mutableStateOf(settings.emailHost) }
    var port by remember(settings.emailPort) { mutableStateOf(settings.emailPort.toString()) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var autoSync by remember(settings.emailAutoSync) { mutableStateOf(settings.emailAutoSync) }
    var lookback by remember(settings.emailLookbackDays) { mutableStateOf(settings.emailLookbackDays.toString()) }
    val emlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importEml) }

    Scaffold(
        topBar = { SimpleTopBar("Email import", onBack = { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (busy || work?.state == WorkInfo.State.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
            SectionCard(title = "Connect your mailbox (IMAP)") {
                Text(
                    "Bank alerts and receipts (Swiggy, Amazon, Uber, utility bills…) are read from your inbox over a secure IMAP connection directly between this phone and your mail provider.\n\n" +
                        "Gmail: turn on 2-Step Verification, then create an App Password at myaccount.google.com → Security → App passwords, and paste the 16-character password below. " +
                        "The Gmail API is not used because it requires registering this app with Google Cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        address,
                        { v -> address = v.trim(); if (host.isBlank() || host == vm.defaultHost(settings.emailAddress)) host = if (v.contains('@')) vm.defaultHost(v.trim()) else host },
                        label = { Text("Email address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(host, { host = it.trim() }, label = { Text("IMAP server") }, singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        password, { password = it },
                        label = { Text(if (settings.emailPasswordEnc.isNotBlank()) "App password (saved – leave blank to keep)" else "App password") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show password") } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(lookback, { lookback = it.filter(Char::isDigit) }, label = { Text("Look back (days) for the first sync") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sync automatically every few hours", modifier = Modifier.weight(1f))
                        Switch(autoSync, { autoSync = it })
                    }
                    Button(
                        enabled = !busy && address.contains('@') && host.isNotBlank() && (password.isNotBlank() || settings.emailPasswordEnc.isNotBlank()),
                        onClick = { vm.testAndSave(address, host, port.toIntOrNull() ?: 993, password, autoSync, lookback.toIntOrNull()?.coerceIn(1, 730) ?: 60) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Test connection & save") }
                }
            }
            if (settings.emailConfigured) {
                SectionCard(title = "Sync") {
                    Text("Last synced ${Fmt.relative(settings.emailLastSyncAt)}", style = MaterialTheme.typography.bodyMedium)
                    if (settings.emailLastError.isNotBlank()) Text(settings.emailLastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    work?.let { w ->
                        if (w.state == WorkInfo.State.SUCCEEDED) {
                            Text(
                                "Last run: ${w.outputData.getInt("checked", 0)} relevant emails, ${w.outputData.getInt("created", 0)} new transactions",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { vm.sync(false) }) { Text("Sync now") }
                        OutlinedButton(onClick = { vm.sync(true) }) { Text("Rescan") }
                        TextButton(onClick = vm::disconnect) { Text("Disconnect") }
                    }
                }
            }
            SectionCard(title = "Import an email file (.eml)") {
                Text(
                    "Without connecting a mailbox you can save an email as .eml (e.g. Gmail on a computer → ⋮ → Download message) or share it to this app. Bank statement attachments can then be imported.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { emlPicker.launch(arrayOf("message/rfc822", "application/octet-stream", "*/*")) }) { Text("Choose .eml file") }
                attachments.forEach { f ->
                    TextButton(onClick = { nav.navigate(Routes.statement(Uri.fromFile(f))) }) { Text("Import attachment ${f.name}") }
                }
            }
        }
    }
}
