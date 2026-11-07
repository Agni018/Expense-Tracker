package com.expensetracker.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.expensetracker.app.data.repo.AccountRepository
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.di.ApplicationScope
import com.expensetracker.app.notify.Notifier
import com.expensetracker.app.work.WorkScheduler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ExpenseApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var categories: CategoryRepository
    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var scheduler: WorkScheduler
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        notifier.createChannels()
        scope.launch {
            categories.seedDefaultsIfEmpty()
            accounts.ensureCashAccount()
        }
        scheduler.schedulePeriodic()
    }
}
