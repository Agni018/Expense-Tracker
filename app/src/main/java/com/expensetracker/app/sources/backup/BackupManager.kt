package com.expensetracker.app.sources.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.AppDatabase
import com.expensetracker.app.data.db.BudgetEntity
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.GoalContributionEntity
import com.expensetracker.app.data.db.GoalEntity
import com.expensetracker.app.data.db.RuleEntity
import com.expensetracker.app.data.db.SourceRecordEntity
import com.expensetracker.app.data.db.SplitEntity
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.data.repo.ImportRepository
import com.expensetracker.app.domain.model.AccountType
import com.expensetracker.app.domain.model.CategoryKind
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.ImportKind
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.RuleMatchType
import com.expensetracker.app.domain.model.RuleOrigin
import com.expensetracker.app.domain.model.SourceOutcome
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JSON backup of everything the user created. Restore merges into the current data and skips
 * records that already exist (same transaction fingerprint, category name, account, rule...).
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val imports: ImportRepository,
) {
    data class RestoreResult(val added: Int, val skipped: Int, val categories: Int, val accounts: Int)

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val txns = db.transactionDao().all()
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("categories", JSONArray(db.categoryDao().all().map { it.toJson() }))
            .put("accounts", JSONArray(db.accountDao().all().map { it.toJson() }))
            .put("transactions", JSONArray(txns.map { it.toJson() }))
            .put("splits", JSONArray(db.splitDao().all().map { it.toJson() }))
            .put("rules", JSONArray(db.ruleDao().all().map { it.toJson() }))
            .put("budgets", JSONArray(db.budgetDao().all().map { it.toJson() }))
            .put("goals", JSONArray(db.goalDao().all().map { it.toJson() }))
            .put("contributions", JSONArray(db.goalDao().allContributions().map { it.toJson() }))
            .put("sources", JSONArray(db.sourceRecordDao().all().map { it.toJson() }))
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString().toByteArray()) }
            ?: throw IllegalStateException("Could not write the backup file")
        txns.size
    }

    suspend fun restore(uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalArgumentException("Could not open the backup file")
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root == null || root.optString("format") != FORMAT) throw IllegalArgumentException("This is not an Expense Tracker backup file")
        val importId = imports.start(ImportKind.BACKUP_RESTORE, uri.lastPathSegment)
        try {
            val result = db.withTransaction { merge(root) }
            imports.finish(importId, ImportRepository.Counts(total = result.added + result.skipped, imported = result.added, duplicates = result.skipped))
            result
        } catch (e: Exception) {
            imports.finish(importId, ImportRepository.Counts(), error = e.message ?: "Restore failed")
            throw e
        }
    }

    private suspend fun merge(root: JSONObject): RestoreResult {
        val categoryDao = db.categoryDao()
        val existingCategories = categoryDao.all().associateBy { it.name.lowercase() }
        val categoryMap = HashMap<Long, Long>()
        var newCategories = 0
        root.optJSONArray("categories").objects().forEach { o ->
            val c = categoryFrom(o)
            val match = existingCategories[c.name.lowercase()]
            categoryMap[c.id] = match?.id ?: categoryDao.insert(c.copy(id = 0)).also { newCategories++ }
        }

        val accountDao = db.accountDao()
        val existingAccounts = accountDao.all()
        val accountMap = HashMap<Long, Long>()
        var newAccounts = 0
        root.optJSONArray("accounts").objects().forEach { o ->
            val a = accountFrom(o)
            val match = existingAccounts.firstOrNull { (a.last4 != null && it.last4 == a.last4 && it.type == a.type) || it.name.equals(a.name, true) }
            accountMap[a.id] = match?.id ?: accountDao.insert(a.copy(id = 0)).also { newAccounts++ }
        }

        val txDao = db.transactionDao()
        val fingerprints = txDao.all().associateBy { fingerprint(it) }.mapValues { it.value.id }.toMutableMap()
        val txMap = HashMap<Long, Long>()
        val insertedIds = HashSet<Long>()
        var added = 0
        var skipped = 0
        root.optJSONArray("transactions").objects().forEach { o ->
            val t = transactionFrom(o)
            val fp = fingerprint(t)
            val existing = fingerprints[fp]
            if (existing != null) {
                txMap[t.id] = existing
                skipped++
            } else {
                val id = txDao.insert(
                    t.copy(
                        id = 0,
                        categoryId = t.categoryId?.let { categoryMap[it] },
                        accountId = t.accountId?.let { accountMap[it] },
                        possibleDuplicateOf = null,
                        linkedTxnId = null,
                        receiptPath = null,
                    ),
                )
                txMap[t.id] = id
                insertedIds += id
                fingerprints[fp] = id
                added++
            }
        }
        root.optJSONArray("splits").objects().forEach { o ->
            val newTxn = txMap[o.getLong("transactionId")]?.takeIf { it in insertedIds } ?: return@forEach
            db.splitDao().insertAll(listOf(SplitEntity(transactionId = newTxn, categoryId = o.optLongOrNull("categoryId")?.let { categoryMap[it] }, amountMinor = o.getLong("amountMinor"), note = o.optStringOrNull("note"))))
        }
        root.optJSONArray("sources").objects().forEach { o ->
            db.sourceRecordDao().insert(sourceFrom(o).let { it.copy(id = 0, transactionId = it.transactionId?.let { id -> txMap[id] }, importId = null) })
        }
        val rules = db.ruleDao().all()
        root.optJSONArray("rules").objects().forEach { o ->
            val r = ruleFrom(o)
            val category = categoryMap[r.categoryId] ?: return@forEach
            if (rules.none { it.pattern.equals(r.pattern, true) && it.matchType == r.matchType }) db.ruleDao().insert(r.copy(id = 0, categoryId = category))
        }
        val budgets = db.budgetDao().all()
        root.optJSONArray("budgets").objects().forEach { o ->
            val categoryId = o.optLongOrNull("categoryId")?.let { categoryMap[it] ?: return@forEach }
            if (budgets.none { it.categoryId == categoryId }) {
                db.budgetDao().insert(BudgetEntity(categoryId = categoryId, amountMinor = o.getLong("amountMinor"), rollover = o.optBoolean("rollover"), createdAt = o.optLong("createdAt")))
            }
        }
        val goals = db.goalDao().all()
        val goalMap = HashMap<Long, Long>()
        root.optJSONArray("goals").objects().forEach { o ->
            val g = GoalEntity(name = o.getString("name"), emoji = o.optString("emoji", "🎯"), targetMinor = o.getLong("targetMinor"), targetDate = o.optLongOrNull("targetDate"), colorArgb = o.optInt("colorArgb"), isArchived = o.optBoolean("isArchived"), createdAt = o.optLong("createdAt"))
            val match = goals.firstOrNull { it.name.equals(g.name, true) }
            if (match == null) goalMap[o.getLong("id")] = db.goalDao().insert(g)
        }
        root.optJSONArray("contributions").objects().forEach { o ->
            val goalId = goalMap[o.getLong("goalId")] ?: return@forEach
            db.goalDao().insertContribution(GoalContributionEntity(goalId = goalId, amountMinor = o.getLong("amountMinor"), date = o.getLong("date"), note = o.optStringOrNull("note")))
        }
        return RestoreResult(added, skipped, newCategories, newAccounts)
    }

    private fun fingerprint(t: TransactionEntity) =
        "${t.timestamp / 60_000}|${t.amountMinor}|${t.direction}|${t.reference ?: t.merchant?.lowercase() ?: ""}"

    // ---- JSON mapping ----

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)
    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
    private fun JSONObject.putOpt2(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    private fun CategoryEntity.toJson() = JSONObject().put("id", id).put("name", name).put("emoji", emoji).put("colorArgb", colorArgb)
        .put("kind", kind.name).put("isDefault", isDefault).put("sortOrder", sortOrder).put("isArchived", isArchived)

    private fun categoryFrom(o: JSONObject) = CategoryEntity(
        id = o.getLong("id"), name = o.getString("name"), emoji = o.optString("emoji", "📦"), colorArgb = o.optInt("colorArgb"),
        kind = CategoryKind.valueOf(o.optString("kind", "EXPENSE")), isDefault = o.optBoolean("isDefault"), sortOrder = o.optInt("sortOrder"),
        isArchived = o.optBoolean("isArchived"),
    )

    private fun AccountEntity.toJson() = JSONObject().put("id", id).put("name", name).put("type", type.name).putOpt2("institution", institution)
        .putOpt2("last4", last4).put("openingBalanceMinor", openingBalanceMinor).put("openingBalanceAt", openingBalanceAt)
        .put("colorArgb", colorArgb).put("isArchived", isArchived).put("autoDetected", autoDetected).put("createdAt", createdAt)

    private fun accountFrom(o: JSONObject) = AccountEntity(
        id = o.getLong("id"), name = o.getString("name"), type = AccountType.valueOf(o.getString("type")), institution = o.optStringOrNull("institution"),
        last4 = o.optStringOrNull("last4"), openingBalanceMinor = o.optLong("openingBalanceMinor"), openingBalanceAt = o.optLong("openingBalanceAt"),
        colorArgb = o.optInt("colorArgb"), isArchived = o.optBoolean("isArchived"), autoDetected = o.optBoolean("autoDetected"), createdAt = o.optLong("createdAt"),
    )

    private fun TransactionEntity.toJson() = JSONObject().put("id", id).put("amountMinor", amountMinor).put("currency", currency)
        .put("timestamp", timestamp).put("direction", direction.name).put("type", type.name).put("status", status.name)
        .putOpt2("merchant", merchant).putOpt2("rawMerchant", rawMerchant).putOpt2("description", description).putOpt2("categoryId", categoryId)
        .putOpt2("accountId", accountId).put("paymentMethod", paymentMethod.name).putOpt2("reference", reference).putOpt2("upiId", upiId)
        .putOpt2("orderId", orderId).putOpt2("notes", notes).put("tags", tags).put("sourceType", sourceType.name).put("confidence", confidence.toDouble())
        .put("needsReview", needsReview).putOpt2("reviewReason", reviewReason).put("isSplit", isSplit).put("userEdited", userEdited)
        .putOpt2("categoryReason", categoryReason).putOpt2("balanceAfterMinor", balanceAfterMinor).put("createdAt", createdAt).put("updatedAt", updatedAt)

    private fun transactionFrom(o: JSONObject) = TransactionEntity(
        id = o.getLong("id"), amountMinor = o.getLong("amountMinor"), currency = o.optString("currency", "INR"), timestamp = o.getLong("timestamp"),
        direction = Direction.valueOf(o.getString("direction")), type = TxnType.valueOf(o.getString("type")), status = TxnStatus.valueOf(o.getString("status")),
        merchant = o.optStringOrNull("merchant"), rawMerchant = o.optStringOrNull("rawMerchant"), description = o.optStringOrNull("description"),
        categoryId = o.optLongOrNull("categoryId"), accountId = o.optLongOrNull("accountId"), paymentMethod = PaymentMethod.valueOf(o.getString("paymentMethod")),
        reference = o.optStringOrNull("reference"), upiId = o.optStringOrNull("upiId"), orderId = o.optStringOrNull("orderId"), notes = o.optStringOrNull("notes"),
        tags = o.optString("tags"), sourceType = SourceType.valueOf(o.getString("sourceType")), confidence = o.optDouble("confidence", 1.0).toFloat(),
        needsReview = o.optBoolean("needsReview"), reviewReason = o.optStringOrNull("reviewReason"), isSplit = o.optBoolean("isSplit"),
        userEdited = o.optBoolean("userEdited"), categoryReason = o.optStringOrNull("categoryReason"), balanceAfterMinor = o.optLongOrNull("balanceAfterMinor"),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
    )

    private fun SplitEntity.toJson() = JSONObject().put("transactionId", transactionId).putOpt2("categoryId", categoryId).put("amountMinor", amountMinor).putOpt2("note", note)

    private fun RuleEntity.toJson() = JSONObject().put("pattern", pattern).put("matchType", matchType.name).put("categoryId", categoryId)
        .putOpt2("typeOverride", typeOverride?.name).put("origin", origin.name).put("hitCount", hitCount).put("createdAt", createdAt)

    private fun ruleFrom(o: JSONObject) = RuleEntity(
        pattern = o.getString("pattern"), matchType = RuleMatchType.valueOf(o.getString("matchType")), categoryId = o.getLong("categoryId"),
        typeOverride = o.optStringOrNull("typeOverride")?.let { TxnType.valueOf(it) }, origin = RuleOrigin.valueOf(o.getString("origin")),
        hitCount = o.optInt("hitCount"), createdAt = o.optLong("createdAt"),
    )

    private fun BudgetEntity.toJson() = JSONObject().putOpt2("categoryId", categoryId).put("amountMinor", amountMinor).put("rollover", rollover).put("createdAt", createdAt)

    private fun GoalEntity.toJson() = JSONObject().put("id", id).put("name", name).put("emoji", emoji).put("targetMinor", targetMinor)
        .putOpt2("targetDate", targetDate).put("colorArgb", colorArgb).put("isArchived", isArchived).put("createdAt", createdAt)

    private fun GoalContributionEntity.toJson() = JSONObject().put("goalId", goalId).put("amountMinor", amountMinor).put("date", date).putOpt2("note", note)

    private fun SourceRecordEntity.toJson() = JSONObject().putOpt2("transactionId", transactionId).put("sourceType", sourceType.name)
        .put("contentHash", contentHash).putOpt2("bodyHash", bodyHash).putOpt2("sender", sender).putOpt2("rawText", rawText).put("receivedAt", receivedAt)
        .put("outcome", outcome.name).putOpt2("note", note).putOpt2("parser", parser).putOpt2("parsedAmountMinor", parsedAmountMinor)
        .putOpt2("parsedDirection", parsedDirection?.name).putOpt2("parsedType", parsedType?.name).putOpt2("parsedTimestamp", parsedTimestamp)
        .putOpt2("parsedMerchant", parsedMerchant).putOpt2("parsedAccountLast4", parsedAccountLast4).putOpt2("parsedReference", parsedReference)
        .put("createdAt", createdAt)

    private fun sourceFrom(o: JSONObject) = SourceRecordEntity(
        transactionId = o.optLongOrNull("transactionId"), sourceType = SourceType.valueOf(o.getString("sourceType")), contentHash = o.getString("contentHash"),
        bodyHash = o.optStringOrNull("bodyHash"), sender = o.optStringOrNull("sender"), rawText = o.optStringOrNull("rawText"), receivedAt = o.getLong("receivedAt"),
        outcome = SourceOutcome.valueOf(o.getString("outcome")), note = o.optStringOrNull("note"), parser = o.optStringOrNull("parser"),
        parsedAmountMinor = o.optLongOrNull("parsedAmountMinor"), parsedDirection = o.optStringOrNull("parsedDirection")?.let { Direction.valueOf(it) },
        parsedType = o.optStringOrNull("parsedType")?.let { TxnType.valueOf(it) }, parsedTimestamp = o.optLongOrNull("parsedTimestamp"),
        parsedMerchant = o.optStringOrNull("parsedMerchant"), parsedAccountLast4 = o.optStringOrNull("parsedAccountLast4"),
        parsedReference = o.optStringOrNull("parsedReference"), createdAt = o.optLong("createdAt"),
    )

    companion object {
        const val FORMAT = "expense-tracker-backup"
    }
}
