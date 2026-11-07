package com.expensetracker.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val userName: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentIndex: Int = 0,
    val dynamicColor: Boolean = false,
    val smsAutoImport: Boolean = true,
    val notificationCapture: Boolean = true,
    val notifyNewTransactions: Boolean = true,
    val budgetAlerts: Boolean = true,
    val billReminders: Boolean = true,
    val smsScanMonths: Int = 6,
    val lastSmsScanAt: Long = 0,
    val smsCursor: Long = 0,
    val emailAddress: String = "",
    val emailHost: String = "",
    val emailPort: Int = 993,
    val emailPasswordEnc: String = "",
    val emailAutoSync: Boolean = true,
    val emailLookbackDays: Int = 60,
    val emailLastSyncAt: Long = 0,
    val emailCursor: Long = 0,
    val emailLastError: String = "",
) {
    val emailConfigured: Boolean get() = emailAddress.isNotBlank() && emailHost.isNotBlank() && emailPasswordEnc.isNotBlank()
}

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val userName = stringPreferencesKey("user_name")
        val theme = stringPreferencesKey("theme_mode")
        val accent = intPreferencesKey("accent")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val smsAuto = booleanPreferencesKey("sms_auto")
        val notifCapture = booleanPreferencesKey("notification_capture")
        val notifyNew = booleanPreferencesKey("notify_new")
        val budgetAlerts = booleanPreferencesKey("budget_alerts")
        val billReminders = booleanPreferencesKey("bill_reminders")
        val smsMonths = intPreferencesKey("sms_scan_months")
        val lastSmsScan = longPreferencesKey("last_sms_scan")
        val smsCursor = longPreferencesKey("sms_cursor")
        val emailAddress = stringPreferencesKey("email_address")
        val emailHost = stringPreferencesKey("email_host")
        val emailPort = intPreferencesKey("email_port")
        val emailPassword = stringPreferencesKey("email_password_enc")
        val emailAuto = booleanPreferencesKey("email_auto")
        val emailLookback = intPreferencesKey("email_lookback")
        val emailLastSync = longPreferencesKey("email_last_sync")
        val emailCursor = longPreferencesKey("email_cursor")
        val emailError = stringPreferencesKey("email_error")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            onboardingDone = p[K.onboarding] ?: false,
            userName = p[K.userName] ?: "",
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            accentIndex = p[K.accent] ?: 0,
            dynamicColor = p[K.dynamic] ?: false,
            smsAutoImport = p[K.smsAuto] ?: true,
            notificationCapture = p[K.notifCapture] ?: true,
            notifyNewTransactions = p[K.notifyNew] ?: true,
            budgetAlerts = p[K.budgetAlerts] ?: true,
            billReminders = p[K.billReminders] ?: true,
            smsScanMonths = p[K.smsMonths] ?: 6,
            lastSmsScanAt = p[K.lastSmsScan] ?: 0,
            smsCursor = p[K.smsCursor] ?: 0,
            emailAddress = p[K.emailAddress] ?: "",
            emailHost = p[K.emailHost] ?: "",
            emailPort = p[K.emailPort] ?: 993,
            emailPasswordEnc = p[K.emailPassword] ?: "",
            emailAutoSync = p[K.emailAuto] ?: true,
            emailLookbackDays = p[K.emailLookback] ?: 60,
            emailLastSyncAt = p[K.emailLastSync] ?: 0,
            emailCursor = p[K.emailCursor] ?: 0,
            emailLastError = p[K.emailError] ?: "",
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone(done: Boolean) = store.edit { it[K.onboarding] = done }
    suspend fun setUserName(name: String) = store.edit { it[K.userName] = name.trim() }
    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[K.theme] = mode.name }
    suspend fun setAccent(index: Int) = store.edit { it[K.accent] = index }
    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[K.dynamic] = enabled }
    suspend fun setSmsAutoImport(enabled: Boolean) = store.edit { it[K.smsAuto] = enabled }
    suspend fun setNotificationCapture(enabled: Boolean) = store.edit { it[K.notifCapture] = enabled }
    suspend fun setNotifyNew(enabled: Boolean) = store.edit { it[K.notifyNew] = enabled }
    suspend fun setBudgetAlerts(enabled: Boolean) = store.edit { it[K.budgetAlerts] = enabled }
    suspend fun setBillReminders(enabled: Boolean) = store.edit { it[K.billReminders] = enabled }
    suspend fun setSmsScanMonths(months: Int) = store.edit { it[K.smsMonths] = months }

    suspend fun recordSmsScan(cursor: Long, at: Long) = store.edit {
        it[K.smsCursor] = maxOf(cursor, it[K.smsCursor] ?: 0)
        it[K.lastSmsScan] = at
    }

    suspend fun resetSmsCursor() = store.edit { it[K.smsCursor] = 0 }

    suspend fun saveEmailConfig(address: String, host: String, port: Int, passwordEnc: String?, autoSync: Boolean, lookbackDays: Int) =
        store.edit {
            it[K.emailAddress] = address.trim()
            it[K.emailHost] = host.trim()
            it[K.emailPort] = port
            if (passwordEnc != null) it[K.emailPassword] = passwordEnc
            it[K.emailAuto] = autoSync
            it[K.emailLookback] = lookbackDays
        }

    suspend fun clearEmailConfig() = store.edit {
        it.remove(K.emailAddress)
        it.remove(K.emailHost)
        it.remove(K.emailPassword)
        it.remove(K.emailCursor)
        it.remove(K.emailError)
    }

    suspend fun recordEmailSync(cursor: Long, at: Long, error: String?) = store.edit {
        if (error == null) {
            it[K.emailCursor] = maxOf(cursor, it[K.emailCursor] ?: 0)
            it[K.emailLastSync] = at
            it[K.emailError] = ""
        } else {
            it[K.emailError] = error
        }
    }

    suspend fun resetEmailCursor() = store.edit { it[K.emailCursor] = 0 }
}
