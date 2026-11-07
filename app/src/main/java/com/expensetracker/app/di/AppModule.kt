package com.expensetracker.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.expensetracker.app.data.db.AppDatabase
import com.expensetracker.app.domain.parser.EmailParser
import com.expensetracker.app.domain.parser.NotificationParser
import com.expensetracker.app.domain.parser.SmsParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.ZoneId
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides fun transactionDao(db: AppDatabase) = db.transactionDao()
    @Provides fun sourceRecordDao(db: AppDatabase) = db.sourceRecordDao()
    @Provides fun accountDao(db: AppDatabase) = db.accountDao()
    @Provides fun categoryDao(db: AppDatabase) = db.categoryDao()
    @Provides fun ruleDao(db: AppDatabase) = db.ruleDao()
    @Provides fun splitDao(db: AppDatabase) = db.splitDao()
    @Provides fun budgetDao(db: AppDatabase) = db.budgetDao()
    @Provides fun recurringDao(db: AppDatabase) = db.recurringDao()
    @Provides fun goalDao(db: AppDatabase) = db.goalDao()
    @Provides fun importDao(db: AppDatabase) = db.importDao()

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides fun zone(): ZoneId = ZoneId.systemDefault()

    @Provides fun smsParser(zone: ZoneId) = SmsParser(zone)
    @Provides fun notificationParser(zone: ZoneId) = NotificationParser(zone)
    @Provides fun emailParser(zone: ZoneId) = EmailParser(zone)

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
