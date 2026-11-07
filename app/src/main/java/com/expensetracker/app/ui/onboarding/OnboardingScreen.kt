package com.expensetracker.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.data.repo.AccountRepository
import com.expensetracker.app.ui.components.IconBadge
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.settings.SmsScanCard
import com.expensetracker.app.ui.theme.LocalFinanceColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val settings: SettingsRepository, accounts: AccountRepository) : ViewModel() {
    val accounts = accounts.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun finish(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) settings.setUserName(name)
        settings.setOnboardingDone(true)
    }
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val finance = LocalFinanceColors.current

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { vm.finish(name) }) { Text("Skip") }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (page) {
                    0 -> {
                        Spacer(Modifier.height(32.dp))
                        Box(
                            Modifier.size(96.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(finance.heroStart, finance.heroEnd))),
                            contentAlignment = Alignment.Center,
                        ) { Text("₹", style = MaterialTheme.typography.displaySmall, color = finance.onHero) }
                        Spacer(Modifier.height(24.dp))
                        Text("Know where your money goes", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Expense Tracker reads your bank alerts, payment notifications, emails and statements, then builds one clean, de-duplicated history with charts, budgets and insights.",
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        OutlinedTextField(name, { name = it }, label = { Text("What should we call you? (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    1 -> {
                        Spacer(Modifier.height(24.dp))
                        Text("How it works", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(20.dp))
                        Feature(Icons.Rounded.Sms, "Bank & UPI SMS", "Debits, credits, card spends, ATM withdrawals and refunds are detected automatically, including new messages as they arrive.")
                        Feature(Icons.Rounded.NotificationsActive, "Payment app notifications", "Google Pay, PhonePe, Paytm and bank app alerts (optional).")
                        Feature(Icons.Rounded.Email, "Email", "Receipts from Swiggy, Zomato, Amazon, Uber and bank alerts via IMAP.")
                        Feature(Icons.Rounded.Description, "Statements", "PDF, CSV and Excel bank statements, PhonePe and Google Pay exports.")
                        Feature(Icons.Rounded.CameraAlt, "Receipts", "Scan a bill with the camera.")
                        Feature(Icons.Rounded.Lock, "Private", "Everything is processed and stored on this phone.")
                    }
                    2 -> {
                        Spacer(Modifier.height(24.dp))
                        IconBadge(Icons.Rounded.Sms, size = 72.dp)
                        Spacer(Modifier.height(16.dp))
                        Text("Read your bank SMS", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Allow SMS access and scan your inbox to import past transactions. OTPs, offers and personal messages are ignored and not stored.",
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        SectionCard { SmsScanCard() }
                    }
                    else -> {
                        Spacer(Modifier.height(24.dp))
                        IconBadge(Icons.Rounded.AccountBalance, size = 72.dp)
                        Spacer(Modifier.height(16.dp))
                        Text("Your accounts", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Accounts are added automatically from the last digits in your alerts. You can rename them, add opening balances or create cards and wallets later in Settings → Accounts.",
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        SectionCard {
                            if (accounts.isEmpty()) Text("No accounts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            accounts.forEach { a ->
                                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(a.name, modifier = Modifier.weight(1f))
                                    Text(a.type.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Feature(Icons.Rounded.AutoAwesome, "Tip", "Open a transaction and change its category once or twice – the app learns and applies it next time.")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                repeat(4) { i ->
                    Box(
                        Modifier.size(width = if (pager.currentPage == i) 22.dp else 8.dp, height = 8.dp).clip(CircleShape)
                            .background(if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Button(onClick = {
                if (pager.currentPage < 3) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } else vm.finish(name)
            }) { Text(if (pager.currentPage < 3) "Next" else "Open dashboard") }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        IconBadge(icon, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
