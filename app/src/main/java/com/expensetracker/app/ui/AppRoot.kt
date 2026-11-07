package com.expensetracker.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.expensetracker.app.data.prefs.AppSettings
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.ui.analytics.AnalyticsScreen
import com.expensetracker.app.ui.assistant.AssistantScreen
import com.expensetracker.app.ui.budgets.BudgetsScreen
import com.expensetracker.app.ui.home.HomeScreen
import com.expensetracker.app.ui.imports.EmailSettingsScreen
import com.expensetracker.app.ui.imports.ImportHistoryScreen
import com.expensetracker.app.ui.imports.ParserTestScreen
import com.expensetracker.app.ui.imports.ReceiptScanScreen
import com.expensetracker.app.ui.imports.StatementImportScreen
import com.expensetracker.app.ui.onboarding.OnboardingScreen
import com.expensetracker.app.ui.review.ReviewScreen
import com.expensetracker.app.ui.settings.AccountsScreen
import com.expensetracker.app.ui.settings.BackupReportsScreen
import com.expensetracker.app.ui.settings.CategoriesScreen
import com.expensetracker.app.ui.settings.RulesScreen
import com.expensetracker.app.ui.settings.SettingsScreen
import com.expensetracker.app.ui.theme.AppTheme
import com.expensetracker.app.ui.transactions.EditTransactionScreen
import com.expensetracker.app.ui.transactions.TransactionDetailScreen
import com.expensetracker.app.ui.transactions.TransactionsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface LaunchRequest {
    data class OpenTransaction(val id: Long) : LaunchRequest
    data class Open(val destination: String) : LaunchRequest
    data class SharedText(val text: String) : LaunchRequest
    data class SharedFile(val uri: Uri, val mimeType: String?) : LaunchRequest
}

object Routes {
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val ANALYTICS = "analytics"
    const val BUDGETS = "budgets"
    const val SETTINGS = "settings"
    const val TXN = "txn/{id}"
    const val EDIT = "edit?id={id}&sourceId={sourceId}"
    const val FILTERED = "filtered?categoryId={categoryId}&merchant={merchant}&start={start}&end={end}&accountId={accountId}&title={title}"
    const val REVIEW = "review"
    const val ACCOUNTS = "accounts"
    const val CATEGORIES = "categories"
    const val RULES = "rules"
    const val IMPORTS = "imports"
    const val STATEMENT = "statement?uri={uri}&mime={mime}&importId={importId}"
    const val RECEIPT = "receipt?uri={uri}"
    const val EMAIL = "email"
    const val PARSER = "parser?text={text}"
    const val BACKUP = "backup"
    const val ASSISTANT = "assistant"

    fun txn(id: Long) = "txn/$id"
    fun edit(id: Long? = null, sourceId: Long? = null) = "edit?id=${id ?: -1}&sourceId=${sourceId ?: -1}"
    fun filtered(
        title: String,
        categoryId: Long? = null,
        merchant: String? = null,
        start: Long? = null,
        end: Long? = null,
        accountId: Long? = null,
    ) = "filtered?categoryId=${categoryId ?: -1}&merchant=${Uri.encode(merchant.orEmpty())}&start=${start ?: -1}&end=${end ?: -1}" +
        "&accountId=${accountId ?: -1}&title=${Uri.encode(title)}"
    fun statement(uri: Uri? = null, mime: String? = null, importId: Long? = null) =
        "statement?uri=${Uri.encode(uri?.toString().orEmpty())}&mime=${Uri.encode(mime.orEmpty())}&importId=${importId ?: -1}"
    fun receipt(uri: Uri? = null) = "receipt?uri=${Uri.encode(uri?.toString().orEmpty())}"
    fun parser(text: String? = null) = "parser?text=${Uri.encode(text.orEmpty())}"

    val topLevel = listOf(HOME, TRANSACTIONS, ANALYTICS, BUDGETS, SETTINGS)
}

@HiltViewModel
class RootViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    transactions: TransactionRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val reviewCount: StateFlow<Int> = transactions.observeReviewCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@Composable
fun AppRoot(launchRequests: StateFlow<LaunchRequest?>, onLaunchHandled: () -> Unit) {
    val vm: RootViewModel = hiltViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    AppTheme(s.themeMode, s.accentIndex, s.dynamicColor) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (!s.onboardingDone) {
                OnboardingScreen()
            } else {
                val reviewCount by vm.reviewCount.collectAsStateWithLifecycle()
                MainNavigation(launchRequests, onLaunchHandled, reviewCount)
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Rounded.Home),
    Tab(Routes.TRANSACTIONS, "Transactions", Icons.AutoMirrored.Rounded.ReceiptLong),
    Tab(Routes.ANALYTICS, "Analytics", Icons.Rounded.PieChart),
    Tab(Routes.BUDGETS, "Budgets", Icons.Rounded.AccountBalanceWallet),
    Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

@Composable
private fun MainNavigation(launchRequests: StateFlow<LaunchRequest?>, onLaunchHandled: () -> Unit, reviewCount: Int) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val launch by launchRequests.collectAsStateWithLifecycle()

    LaunchedEffect(launch) {
        when (val r = launch ?: return@LaunchedEffect) {
            is LaunchRequest.OpenTransaction -> nav.navigate(Routes.txn(r.id))
            is LaunchRequest.Open -> if (r.destination in Routes.topLevel) nav.switchTab(r.destination)
            is LaunchRequest.SharedText -> nav.navigate(Routes.parser(r.text.take(4000)))
            is LaunchRequest.SharedFile -> when {
                r.mimeType?.startsWith("image/") == true -> nav.navigate(Routes.receipt(r.uri))
                r.mimeType == "message/rfc822" -> nav.navigate(Routes.EMAIL)
                else -> nav.navigate(Routes.statement(r.uri, r.mimeType))
            }
        }
        onLaunchHandled()
    }

    val showChrome = route in Routes.topLevel
    Scaffold(
        bottomBar = {
            AnimatedVisibility(showChrome, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = {
                                if (tab.route == Routes.HOME && reviewCount > 0) {
                                    BadgedBox(badge = { Badge { Text(if (reviewCount > 99) "99+" else reviewCount.toString()) } }) { Icon(tab.icon, null) }
                                } else {
                                    Icon(tab.icon, null)
                                }
                            },
                            // Five tabs on a narrow phone: a slightly smaller label keeps "Transactions" whole.
                            label = { Text(tab.label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(route == Routes.HOME || route == Routes.TRANSACTIONS, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                FloatingActionButton(onClick = { nav.navigate(Routes.edit()) }, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add transaction")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = Routes.HOME, modifier = Modifier) {
                composable(Routes.HOME) { HomeScreen(nav, padding) }
                composable(Routes.TRANSACTIONS) { TransactionsScreen(nav, padding, filtered = false) }
                composable(Routes.ANALYTICS) { AnalyticsScreen(nav, padding) }
                composable(Routes.BUDGETS) { BudgetsScreen(nav, padding) }
                composable(Routes.SETTINGS) { SettingsScreen(nav, padding) }
                composable(Routes.TXN, arguments = listOf(navArgument("id") { type = NavType.LongType })) { TransactionDetailScreen(nav) }
                composable(
                    Routes.EDIT,
                    arguments = listOf(
                        navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("sourceId") { type = NavType.LongType; defaultValue = -1L },
                    ),
                ) { EditTransactionScreen(nav) }
                composable(
                    Routes.FILTERED,
                    arguments = listOf(
                        navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("merchant") { type = NavType.StringType; defaultValue = "" },
                        navArgument("start") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("end") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("accountId") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { TransactionsScreen(nav, PaddingValues(), filtered = true) }
                composable(Routes.REVIEW) { ReviewScreen(nav) }
                composable(Routes.ACCOUNTS) { AccountsScreen(nav) }
                composable(Routes.CATEGORIES) { CategoriesScreen(nav) }
                composable(Routes.RULES) { RulesScreen(nav) }
                composable(Routes.IMPORTS) { ImportHistoryScreen(nav) }
                composable(
                    Routes.STATEMENT,
                    arguments = listOf(
                        navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                        navArgument("mime") { type = NavType.StringType; defaultValue = "" },
                        navArgument("importId") { type = NavType.LongType; defaultValue = -1L },
                    ),
                ) { StatementImportScreen(nav) }
                composable(Routes.RECEIPT, arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" })) { ReceiptScanScreen(nav) }
                composable(Routes.EMAIL) { EmailSettingsScreen(nav) }
                composable(Routes.PARSER, arguments = listOf(navArgument("text") { type = NavType.StringType; defaultValue = "" })) { ParserTestScreen(nav) }
                composable(Routes.BACKUP) { BackupReportsScreen(nav) }
                composable(Routes.ASSISTANT) { AssistantScreen(nav) }
            }
            // Tab screens scroll edge to edge; keep the status bar area opaque so text never runs under the clock.
            if (showChrome) {
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(MaterialTheme.colorScheme.background))
            }
        }
    }
}

fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
