package com.expensetracker.app.data.ingest

import android.util.Log
import androidx.room.withTransaction
import com.expensetracker.app.core.Hashing
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.AppDatabase
import com.expensetracker.app.data.db.SourceRecordEntity
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.domain.engine.Categorizer
import com.expensetracker.app.domain.engine.DuplicateDetector
import com.expensetracker.app.domain.engine.RuleSpec
import com.expensetracker.app.domain.model.AccountType
import com.expensetracker.app.domain.model.DefaultCategories
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.SourceOutcome
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.domain.parser.MerchantDirectory
import com.expensetracker.app.domain.parser.ParsedTransaction
import com.expensetracker.app.domain.parser.TextSignals
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point that turns parsed records from every source into one deduplicated
 * transaction history. Each call is idempotent (source records are unique per content hash)
 * and runs inside one database transaction.
 */
@Singleton
class TransactionIngestor @Inject constructor(private val db: AppDatabase) {

    data class SourceItem(
        val sourceType: SourceType,
        val contentHash: String,
        val sender: String? = null,
        val rawText: String? = null,
        val receivedAt: Long,
        val importId: Long? = null,
        val accountId: Long? = null,
        val receiptPath: String? = null,
    ) {
        val bodyHash: String?
            get() = rawText?.let { Hashing.sha256(sender, it.replace(Regex("\\s+"), " ").trim().lowercase()) }
    }

    sealed interface Outcome {
        data class Created(val transactionId: Long, val needsReview: Boolean) : Outcome
        data class Merged(val transactionId: Long, val reason: String) : Outcome
        data class FlaggedDuplicate(val transactionId: Long, val duplicateOf: Long) : Outcome
        data object AlreadyProcessed : Outcome
    }

    private val txDao = db.transactionDao()
    private val sourceDao = db.sourceRecordDao()
    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()
    private val ruleDao = db.ruleDao()
    private val mutex = Mutex()

    suspend fun isProcessed(type: SourceType, hash: String): Boolean = sourceDao.exists(type, hash)

    /**
     * The live SMS receiver and the inbox scan see the same message with slightly different
     * timestamps; a matching body within ten minutes means it was already handled.
     */
    suspend fun isBodyProcessed(item: SourceItem): Boolean {
        val bodyHash = item.bodyHash ?: return false
        return sourceDao.existsBody(item.sourceType, bodyHash, item.receivedAt - BODY_WINDOW, item.receivedAt + BODY_WINDOW)
    }

    suspend fun ingest(item: SourceItem, parsed: ParsedTransaction): Outcome = mutex.withLock {
        db.withTransaction { ingestLocked(item, parsed) }
    }

    /** Keeps a financial-looking message that could not be parsed so the user can review it. */
    suspend fun recordUnparsed(item: SourceItem, reason: String, amountMinor: Long?) = mutex.withLock {
        Log.i(TAG, "Unparsed ${item.sourceType} from ${item.sender}: $reason")
        sourceDao.insert(
            SourceRecordEntity(
                transactionId = null, sourceType = item.sourceType, contentHash = item.contentHash, bodyHash = item.bodyHash,
                sender = item.sender, rawText = item.rawText?.take(2000), receivedAt = item.receivedAt,
                outcome = SourceOutcome.UNPARSED, note = reason, importId = item.importId, parsedAmountMinor = amountMinor,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Balance-only alerts update the bank-reported balance of a known account. */
    suspend fun updateReportedBalance(last4: String?, balanceMinor: Long, at: Long) = mutex.withLock {
        if (last4 == null) return@withLock
        accountDao.byLast4(last4).filter { it.type != AccountType.CREDIT_CARD }.forEach { account ->
            if ((account.reportedBalanceAt ?: 0) <= at) accountDao.update(account.copy(reportedBalanceMinor = balanceMinor, reportedBalanceAt = at))
        }
    }

    private suspend fun ingestLocked(item: SourceItem, parsed: ParsedTransaction): Outcome {
        if (sourceDao.exists(item.sourceType, item.contentHash)) return Outcome.AlreadyProcessed
        val now = System.currentTimeMillis()
        val account = item.accountId?.let { accountDao.get(it) } ?: resolveAccount(parsed, now)
        val accounts = accountDao.all()
        val reference = TextSignals.normalizeRef(parsed.reference)

        var type = parsed.type
        var status = parsed.status
        val notes = parsed.notes.toMutableList()
        val ownCounterparty = parsed.counterpartyAccountLast4?.let { l4 -> accounts.firstOrNull { it.last4 == l4 && it.id != account?.id } }
        if (ownCounterparty != null && (type == TxnType.EXPENSE || type == TxnType.INCOME)) {
            type = TxnType.TRANSFER
            notes += "Other side is your account ${ownCounterparty.name}"
        }

        val categorizer = Categorizer(
            categoryDao.all().filter { !it.isArchived }.associate { it.name to it.id },
            ruleDao.all().map { RuleSpec(it.id, it.pattern, it.matchType, it.categoryId, it.typeOverride, it.origin) },
        )
        val category = categorizer.categorize(parsed.copy(type = type))
        category.typeOverride?.let { type = it }
        category.ruleId?.let { ruleDao.hit(it) }

        val best = bestMatch(item, parsed, account?.last4, reference, accounts)
        if (best != null && best.second.verdict == DuplicateDetector.Verdict.DUPLICATE) {
            val (existing, decision) = best
            val reason = decision.reasons.joinToString("; ")
            sourceDao.insert(sourceRecord(item, parsed, existing.id, SourceOutcome.MERGED, "Merged with existing transaction: $reason", now))
            txDao.update(enrich(existing, parsed, account, item, reference, categorizer, now))
            return Outcome.Merged(existing.id, reason)
        }
        val possible = best?.takeIf { it.second.verdict == DuplicateDetector.Verdict.POSSIBLE }

        // ---- refunds ----
        var linked: TransactionEntity? = null
        if (type == TxnType.REFUND) {
            linked = findRefundOriginal(parsed)
            if (linked != null && linked.status in setOf(TxnStatus.FAILED, TxnStatus.DECLINED)) {
                status = TxnStatus.REVERSED
                notes += "Reversal of a failed payment (not counted)"
            }
        }

        val reviewReasons = buildList {
            possible?.let { add("Possible duplicate: " + it.second.reasons.joinToString("; ")) }
            if (parsed.confidence < REVIEW_CONFIDENCE) add("Low confidence (${(parsed.confidence * 100).toInt()}%)")
            if (parsed.notes.any { it.contains("verify") || it.contains("unclear") }) add(parsed.notes.first { it.contains("verify") || it.contains("unclear") })
        }
        val entity = TransactionEntity(
            amountMinor = parsed.amountMinor,
            timestamp = parsed.timestamp,
            direction = parsed.direction,
            type = type,
            status = status,
            merchant = parsed.merchant,
            rawMerchant = parsed.merchantRaw,
            description = parsed.description,
            categoryId = linked?.categoryId ?: category.categoryId,
            accountId = account?.id,
            paymentMethod = parsed.paymentMethod,
            reference = reference,
            upiId = parsed.upiId,
            orderId = parsed.orderId,
            sourceType = item.sourceType,
            confidence = parsed.confidence,
            needsReview = reviewReasons.isNotEmpty(),
            reviewReason = reviewReasons.joinToString("\n").ifEmpty { null },
            possibleDuplicateOf = possible?.first?.id,
            linkedTxnId = linked?.id,
            receiptPath = item.receiptPath,
            categoryReason = if (linked != null) "Same category as the original payment" else category.reason,
            balanceAfterMinor = parsed.balanceMinor,
            createdAt = now,
            updatedAt = now,
        )
        val id = txDao.insert(entity)
        sourceDao.insert(
            sourceRecord(
                item, parsed, id, if (possible != null) SourceOutcome.POSSIBLE_DUPLICATE else SourceOutcome.CREATED,
                notes.distinct().joinToString("\n").ifEmpty { null }, now,
            ),
        )
        if (type == TxnType.EXPENSE || type == TxnType.INCOME) pairOwnTransfer(entity.copy(id = id), now)
        if (parsed.balanceMinor != null && account != null && account.type != AccountType.CREDIT_CARD &&
            (account.reportedBalanceAt ?: 0) <= parsed.timestamp
        ) {
            accountDao.update(account.copy(reportedBalanceMinor = parsed.balanceMinor, reportedBalanceAt = parsed.timestamp))
        }
        return if (possible != null) Outcome.FlaggedDuplicate(id, possible.first.id) else Outcome.Created(id, entity.needsReview)
    }

    /** Read-only duplicate check used by import previews. */
    suspend fun findDuplicate(item: SourceItem, parsed: ParsedTransaction, accountLast4: String?): Pair<TransactionEntity, DuplicateDetector.Decision>? {
        if (sourceDao.exists(item.sourceType, item.contentHash)) return null
        return bestMatch(item, parsed, accountLast4 ?: parsed.accountLast4, TextSignals.normalizeRef(parsed.reference), accountDao.all())
    }

    private suspend fun bestMatch(
        item: SourceItem,
        parsed: ParsedTransaction,
        accountLast4: String?,
        reference: String?,
        accounts: List<AccountEntity>,
    ): Pair<TransactionEntity, DuplicateDetector.Decision>? {
        val incoming = DuplicateDetector.Record(
            parsed.amountMinor, parsed.direction, parsed.timestamp, accountLast4 ?: parsed.accountLast4, reference,
            parsed.merchant, setOf(item.sourceType), setOfNotNull(item.bodyHash), MerchantDirectory.match(parsed.merchant) != null,
        )
        val candidates = txDao.findByAmount(parsed.amountMinor, parsed.timestamp - DUP_WINDOW, parsed.timestamp + DUP_WINDOW)
        if (candidates.isEmpty()) return null
        val sourcesByTxn = sourceDao.forTransactions(candidates.map { it.id }).groupBy { it.transactionId }
        val accountsById = accounts.associateBy { it.id }
        return candidates.map { c ->
            val sources = sourcesByTxn[c.id].orEmpty()
            val existing = DuplicateDetector.Record(
                c.amountMinor, c.direction, c.timestamp, c.accountId?.let { accountsById[it]?.last4 }, c.reference, c.merchant,
                sources.map { it.sourceType }.toSet().ifEmpty { setOf(c.sourceType) },
                sources.mapNotNull { it.bodyHash }.toSet(), MerchantDirectory.match(c.merchant) != null,
            )
            c to DuplicateDetector.evaluate(incoming, existing)
        }.filter { it.second.verdict != DuplicateDetector.Verdict.DISTINCT }.maxByOrNull { it.second.score }
    }

    private fun sourceRecord(item: SourceItem, p: ParsedTransaction, txnId: Long?, outcome: SourceOutcome, note: String?, now: Long) =
        SourceRecordEntity(
            transactionId = txnId, sourceType = item.sourceType, contentHash = item.contentHash, bodyHash = item.bodyHash,
            sender = item.sender, rawText = item.rawText?.take(2000), receivedAt = item.receivedAt, outcome = outcome, note = note,
            importId = item.importId, parser = p.parser, parsedAmountMinor = p.amountMinor, parsedDirection = p.direction,
            parsedType = p.type, parsedStatus = p.status, parsedTimestamp = p.timestamp, parsedMerchant = p.merchant,
            parsedAccountLast4 = p.accountLast4, parsedReference = TextSignals.normalizeRef(p.reference),
            parsedMethod = p.paymentMethod, parsedConfidence = p.confidence, createdAt = now,
        )

    /** Fills gaps in an existing transaction from a corroborating source; user edits win. */
    private fun enrich(
        e: TransactionEntity,
        p: ParsedTransaction,
        account: AccountEntity?,
        item: SourceItem,
        reference: String?,
        categorizer: Categorizer,
        now: Long,
    ): TransactionEntity {
        val betterMerchant = !e.userEdited && p.merchant != null &&
            (e.merchant == null || (MerchantDirectory.match(e.merchant) == null && MerchantDirectory.match(p.merchant) != null))
        val merchant = if (betterMerchant) p.merchant else e.merchant
        val recategorized = if (betterMerchant) categorizer.categorize(merchant, e.description, e.type, e.direction) else null
        val corroborated = e.needsReview && e.possibleDuplicateOf == null && p.confidence >= REVIEW_CONFIDENCE
        return e.copy(
            merchant = merchant,
            rawMerchant = e.rawMerchant ?: p.merchantRaw,
            categoryId = recategorized?.categoryId ?: e.categoryId,
            categoryReason = recategorized?.reason ?: e.categoryReason,
            accountId = e.accountId ?: account?.id,
            reference = e.reference ?: reference,
            upiId = e.upiId ?: p.upiId,
            orderId = e.orderId ?: p.orderId,
            paymentMethod = if (e.paymentMethod == PaymentMethod.OTHER) p.paymentMethod else e.paymentMethod,
            status = if (e.status == TxnStatus.PENDING && p.status != TxnStatus.PENDING) p.status else e.status,
            receiptPath = e.receiptPath ?: item.receiptPath,
            balanceAfterMinor = e.balanceAfterMinor ?: p.balanceMinor,
            confidence = maxOf(e.confidence, p.confidence),
            needsReview = if (corroborated) false else e.needsReview,
            reviewReason = if (corroborated) null else e.reviewReason,
            updatedAt = now,
        )
    }

    private suspend fun resolveAccount(p: ParsedTransaction, now: Long): AccountEntity? {
        if (p.paymentMethod == PaymentMethod.CASH && p.accountLast4 == null) return accountDao.cashAccount()
        val last4 = p.accountLast4 ?: return null
        val matches = accountDao.byLast4(last4)
        val wantedType = when {
            p.isCreditCard -> AccountType.CREDIT_CARD
            p.paymentMethod == PaymentMethod.WALLET -> AccountType.WALLET
            else -> AccountType.BANK
        }
        if (matches.isNotEmpty()) {
            return matches.firstOrNull { it.type == wantedType && (p.institution == null || it.institution == null || it.institution == p.institution) }
                ?: matches.firstOrNull { p.institution != null && it.institution == p.institution }
                ?: matches.first()
        }
        val label = p.institution ?: if (wantedType == AccountType.CREDIT_CARD) "Card" else "Account"
        val account = AccountEntity(
            name = "$label ••$last4",
            type = wantedType,
            institution = p.institution,
            last4 = last4,
            colorArgb = ACCOUNT_COLORS[(last4.hashCode() and 0x7fffffff) % ACCOUNT_COLORS.size],
            autoDetected = true,
            createdAt = now,
        )
        return account.copy(id = accountDao.insert(account))
    }

    private suspend fun findRefundOriginal(p: ParsedTransaction): TransactionEntity? {
        if (p.merchant == null && p.reference == null) return null
        return txDao.refundCandidates(p.amountMinor, p.timestamp - REFUND_WINDOW, p.timestamp)
            .filter { DuplicateDetector.merchantsMatch(it.merchant, p.merchant) || (p.reference != null && it.reference == TextSignals.normalizeRef(p.reference)) }
            .sortedWith(compareBy<TransactionEntity> { if (it.amountMinor == p.amountMinor) 0 else 1 }.thenByDescending { it.timestamp })
            .firstOrNull()
    }

    /**
     * A debit and a credit of the same amount on two of the user's accounts within 30 minutes
     * are very likely a self transfer. They are re-typed but sent to review so the user can undo.
     */
    private suspend fun pairOwnTransfer(t: TransactionEntity, now: Long) {
        if (t.accountId == null) return
        if (t.paymentMethod !in setOf(PaymentMethod.UPI, PaymentMethod.IMPS, PaymentMethod.NEFT, PaymentMethod.RTGS, PaymentMethod.NET_BANKING, PaymentMethod.OTHER)) return
        val other = txDao.findByAmount(t.amountMinor, t.timestamp - PAIR_WINDOW, t.timestamp + PAIR_WINDOW).firstOrNull {
            it.id != t.id && it.accountId != null && it.accountId != t.accountId && it.direction != t.direction &&
                (it.type == TxnType.EXPENSE || it.type == TxnType.INCOME) && it.status == TxnStatus.COMPLETED && !it.userEdited
        } ?: return
        if (t.status != TxnStatus.COMPLETED) return
        val reason = "Matched as a transfer between your own accounts (same amount within 30 minutes)"
        val transfersCategory = categoryDao.all().firstOrNull { it.name == DefaultCategories.TRANSFERS }?.id
        listOf(t, other).forEach {
            txDao.update(
                it.copy(
                    type = TxnType.TRANSFER, categoryId = transfersCategory ?: it.categoryId, needsReview = true,
                    reviewReason = listOfNotNull(it.reviewReason, reason).joinToString("\n"), linkedTxnId = if (it.id == t.id) other.id else t.id,
                    categoryReason = "Self transfer", updatedAt = now,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "Ingestor"
        const val REVIEW_CONFIDENCE = 0.6f
        private const val DAY = 86_400_000L
        private const val DUP_WINDOW = 3 * DAY
        private const val REFUND_WINDOW = 120 * DAY
        private const val PAIR_WINDOW = 30 * 60_000L
        private const val BODY_WINDOW = 10 * 60_000L
        val ACCOUNT_COLORS = listOf(0xFF4F46E5, 0xFF0EA5E9, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444, 0xFF8B5CF6, 0xFFEC4899, 0xFF14B8A6).map { it.toInt() }
    }
}
