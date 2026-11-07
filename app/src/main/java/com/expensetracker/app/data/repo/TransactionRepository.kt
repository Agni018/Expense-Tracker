package com.expensetracker.app.data.repo

import androidx.room.withTransaction
import com.expensetracker.app.core.Hashing
import com.expensetracker.app.data.db.AppDatabase
import com.expensetracker.app.data.db.CategoryFeedbackEntity
import com.expensetracker.app.data.db.RuleEntity
import com.expensetracker.app.data.db.SourceRecordEntity
import com.expensetracker.app.data.db.SplitEntity
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.domain.engine.Categorizer
import com.expensetracker.app.domain.engine.LedgerEntry
import com.expensetracker.app.domain.engine.RuleSpec
import com.expensetracker.app.domain.engine.SplitPart
import com.expensetracker.app.domain.model.DateRange
import com.expensetracker.app.domain.model.DefaultCategories
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.RuleMatchType
import com.expensetracker.app.domain.model.RuleOrigin
import com.expensetracker.app.domain.model.SourceOutcome
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.domain.parser.MerchantDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val db: AppDatabase,
    private val zone: ZoneId,
) {
    private val txDao = db.transactionDao()
    private val sourceDao = db.sourceRecordDao()
    private val splitDao = db.splitDao()
    private val ruleDao = db.ruleDao()
    private val categoryDao = db.categoryDao()

    fun observeRange(range: DateRange): Flow<List<TransactionEntity>> =
        txDao.observeRange(range.startMillis(zone), range.endMillisExclusive(zone))

    fun observeAll(): Flow<List<TransactionEntity>> = txDao.observeAll()
    fun observeRecent(limit: Int) = txDao.observeRecent(limit)
    fun observe(id: Long) = txDao.observe(id)
    fun observeSources(id: Long) = sourceDao.observeForTransaction(id)
    fun observeSplits(id: Long) = splitDao.observeForTransaction(id)
    fun observeReview() = txDao.observeNeedsReview()
    fun observeReviewCount() = txDao.observeReviewCount()
    fun observeUnparsed() = sourceDao.observeUnparsed()
    fun observeByMerchant(merchant: String) = txDao.observeByMerchant(merchant)

    /** All transactions, newest first, with matching ledger entries (splits applied). */
    data class TxnData(val transactions: List<TransactionEntity>, val ledger: List<LedgerEntry>)

    fun observeData(): Flow<TxnData> = combine(txDao.observeAll(), splitDao.observeAll()) { txns, splits ->
        val byTxn = splits.groupBy { it.transactionId }
        TxnData(txns, txns.map { t -> t.toLedger(byTxn[t.id].orEmpty().map { SplitPart(it.categoryId, it.amountMinor) }) })
    }

    suspend fun ledgerSince(from: Long): List<LedgerEntry> {
        val splits = splitDao.all().groupBy { it.transactionId }
        return txDao.since(from).map { t -> t.toLedger(splits[t.id].orEmpty().map { SplitPart(it.categoryId, it.amountMinor) }) }
    }

    suspend fun get(id: Long) = txDao.get(id)
    suspend fun splits(id: Long) = splitDao.forTransaction(id)

    /** Manual entries get their own source record so backups and history stay consistent. */
    suspend fun addManual(t: TransactionEntity, sourceType: SourceType = SourceType.MANUAL, rawText: String? = null): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val id = txDao.insert(t.copy(id = 0, sourceType = sourceType, userEdited = true, createdAt = now, updatedAt = now))
        sourceDao.insert(
            SourceRecordEntity(
                transactionId = id, sourceType = sourceType, contentHash = Hashing.sha256("manual", UUID.randomUUID()),
                sender = null, rawText = rawText, receivedAt = now, outcome = SourceOutcome.CREATED, parser = "Manual",
                parsedAmountMinor = t.amountMinor, parsedDirection = t.direction, parsedType = t.type, parsedStatus = t.status,
                parsedTimestamp = t.timestamp, parsedMerchant = t.merchant, parsedMethod = t.paymentMethod, createdAt = now,
            ),
        )
        id
    }

    suspend fun update(t: TransactionEntity) {
        txDao.update(t.copy(userEdited = true, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Changes the category of one transaction. [remember] stores an explicit rule; otherwise a
     * learned rule is created after the user picks the same category for a merchant twice.
     * Returns how many other transactions were updated when [applyToMerchant] is set.
     */
    suspend fun setCategory(id: Long, categoryId: Long, applyToMerchant: Boolean, remember: Boolean): Int = db.withTransaction {
        val t = txDao.get(id) ?: return@withTransaction 0
        val now = System.currentTimeMillis()
        txDao.update(t.copy(categoryId = categoryId, userEdited = true, categoryReason = "Set by you", updatedAt = now))
        val merchant = t.merchant ?: return@withTransaction 0
        val key = MerchantDirectory.compact(merchant)
        if (key.isEmpty()) return@withTransaction 0
        val feedback = ruleDao.feedback(key, categoryId)
        val count = (feedback?.count ?: 0) + 1
        ruleDao.upsertFeedback(CategoryFeedbackEntity(key, categoryId, count, now))
        val existing = ruleDao.exactFor(merchant)
        when {
            remember -> upsertRule(existing, merchant, categoryId, RuleOrigin.USER, now)
            count >= 2 && existing?.origin != RuleOrigin.USER -> upsertRule(existing, merchant, categoryId, RuleOrigin.LEARNED, now)
        }
        if (applyToMerchant) txDao.recategorizeMerchant(merchant, categoryId, now) else 0
    }

    private suspend fun upsertRule(existing: RuleEntity?, merchant: String, categoryId: Long, origin: RuleOrigin, now: Long) {
        if (existing != null) ruleDao.update(existing.copy(categoryId = categoryId, origin = origin))
        else ruleDao.insert(RuleEntity(pattern = merchant, matchType = RuleMatchType.MERCHANT_EXACT, categoryId = categoryId, origin = origin, createdAt = now))
    }

    suspend fun bulkSetCategory(ids: Collection<Long>, categoryId: Long) = db.withTransaction {
        val now = System.currentTimeMillis()
        ids.forEach { id -> txDao.get(id)?.let { txDao.update(it.copy(categoryId = categoryId, userEdited = true, categoryReason = "Set by you", updatedAt = now)) } }
    }

    /** Changing the type also fixes the direction and moves out of a fixed category (e.g. Transfers). */
    suspend fun setType(id: Long, type: TxnType) = db.withTransaction {
        val t = txDao.get(id) ?: return@withTransaction
        val direction = directionFor(type, t.direction)
        val fixedName = DefaultCategories.forType(type)
        val categoryId = when {
            fixedName != null -> categoryDao.all().firstOrNull { it.name == fixedName }?.id ?: t.categoryId
            DefaultCategories.forType(t.type) != null -> categorizer().categorize(t.merchant, t.description, type, direction).categoryId
            else -> t.categoryId
        }
        txDao.update(t.copy(type = type, direction = direction, categoryId = categoryId, userEdited = true, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun categorizer() = Categorizer(
        categoryDao.all().associate { it.name to it.id },
        ruleDao.all().map { RuleSpec(it.id, it.pattern, it.matchType, it.categoryId, it.typeOverride, it.origin) },
    )

    suspend fun bulkSetType(ids: Collection<Long>, type: TxnType) = ids.forEach { setType(it, type) }

    /** Deleted transactions keep their source records so a rescan does not bring them back. */
    suspend fun delete(id: Long) = db.withTransaction {
        sourceDao.detachFromTransaction(id, SourceOutcome.DELETED_BY_USER)
        splitDao.deleteFor(id)
        txDao.delete(id)
    }

    suspend fun bulkDelete(ids: Collection<Long>) = db.withTransaction { ids.forEach { delete(it) } }

    suspend fun setSplits(id: Long, parts: List<SplitEntity>) = db.withTransaction {
        splitDao.deleteFor(id)
        if (parts.isNotEmpty()) splitDao.insertAll(parts.map { it.copy(id = 0, transactionId = id) })
        txDao.get(id)?.let { txDao.update(it.copy(isSplit = parts.isNotEmpty(), userEdited = true, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun setReceipt(id: Long, path: String?) {
        txDao.get(id)?.let { txDao.update(it.copy(receiptPath = path, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun approve(id: Long) {
        txDao.get(id)?.let { txDao.update(it.copy(needsReview = false, reviewReason = null, possibleDuplicateOf = null, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun approveAll(ids: Collection<Long>) = db.withTransaction { ids.forEach { approve(it) } }

    /** Merges a flagged duplicate into the transaction it duplicates. */
    suspend fun mergeInto(duplicateId: Long, targetId: Long) = db.withTransaction {
        val dup = txDao.get(duplicateId) ?: return@withTransaction
        val target = txDao.get(targetId) ?: return@withTransaction
        sourceDao.forTransaction(duplicateId).forEach {
            sourceDao.update(it.copy(transactionId = targetId, outcome = SourceOutcome.MERGED, note = "Merged by you"))
        }
        splitDao.deleteFor(duplicateId)
        txDao.delete(duplicateId)
        txDao.update(
            target.copy(
                merchant = target.merchant ?: dup.merchant,
                accountId = target.accountId ?: dup.accountId,
                reference = target.reference ?: dup.reference,
                receiptPath = target.receiptPath ?: dup.receiptPath,
                notes = listOfNotNull(target.notes, dup.notes).joinToString("\n").ifEmpty { null },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Undoes an automatic merge: the source record becomes its own transaction again. */
    suspend fun separateSource(sourceId: Long): Long? = db.withTransaction {
        val record = sourceDao.get(sourceId) ?: return@withTransaction null
        val original = record.transactionId?.let { txDao.get(it) } ?: return@withTransaction null
        if (sourceDao.forTransaction(original.id).size < 2) return@withTransaction null
        val amount = record.parsedAmountMinor ?: return@withTransaction null
        val direction = record.parsedDirection ?: original.direction
        val type = record.parsedType ?: original.type
        val category = categorizer().categorize(record.parsedMerchant, record.rawText, type, direction)
        val now = System.currentTimeMillis()
        val id = txDao.insert(
            TransactionEntity(
                amountMinor = amount,
                timestamp = record.parsedTimestamp ?: record.receivedAt,
                direction = direction,
                type = type,
                status = record.parsedStatus ?: TxnStatus.COMPLETED,
                merchant = record.parsedMerchant,
                categoryId = category.categoryId,
                accountId = original.accountId.takeIf { record.parsedAccountLast4 == null },
                paymentMethod = record.parsedMethod ?: original.paymentMethod,
                reference = record.parsedReference,
                sourceType = record.sourceType,
                confidence = record.parsedConfidence ?: 0.5f,
                categoryReason = category.reason,
                createdAt = now,
                updatedAt = now,
            ),
        )
        sourceDao.update(record.copy(transactionId = id, outcome = SourceOutcome.UNMERGED, note = "Separated from transaction #${original.id} by you"))
        id
    }

    suspend fun linkUnparsed(sourceId: Long, txnId: Long) {
        sourceDao.get(sourceId)?.let { sourceDao.update(it.copy(transactionId = txnId, outcome = SourceOutcome.CREATED, note = "Added manually from this message")) }
    }

    suspend fun dismissUnparsed(sourceId: Long) {
        sourceDao.get(sourceId)?.let { sourceDao.update(it.copy(outcome = SourceOutcome.DELETED_BY_USER)) }
    }

    fun directionFor(type: TxnType, current: Direction): Direction = when (type) {
        TxnType.EXPENSE, TxnType.CASH_WITHDRAWAL -> Direction.DEBIT
        TxnType.INCOME, TxnType.REFUND, TxnType.REIMBURSEMENT, TxnType.LOAN -> Direction.CREDIT
        else -> current
    }
}
