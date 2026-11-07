package com.expensetracker.app.sources.email

import android.content.Context
import android.net.Uri
import android.util.Log
import com.expensetracker.app.core.Hashing
import com.expensetracker.app.data.ingest.PostIngest
import com.expensetracker.app.data.ingest.TransactionIngestor
import com.expensetracker.app.data.ingest.TransactionIngestor.Outcome
import com.expensetracker.app.data.prefs.SecretStore
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.data.repo.ImportRepository
import com.expensetracker.app.domain.model.ImportKind
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.parser.EmailParser
import com.expensetracker.app.domain.parser.HtmlText
import com.expensetracker.app.domain.parser.MessageParseResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

/**
 * Email import over IMAP (works with Gmail using an app password, Outlook, Yahoo and others)
 * plus .eml files. The Gmail REST API is not used because it needs a Google Cloud OAuth client
 * registered for this app's signing key, which a personal sideloaded build does not have.
 */
@Singleton
class EmailImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val parser: EmailParser,
    private val ingestor: TransactionIngestor,
    private val imports: ImportRepository,
    private val postIngest: PostIngest,
) {
    data class Config(val host: String, val port: Int, val address: String, val password: String)

    data class Result(
        val checked: Int = 0,
        val created: Int = 0,
        val merged: Int = 0,
        val ignored: Int = 0,
        val attachments: List<File> = emptyList(),
        val error: String? = null,
    )

    private val financialHint = Regex(
        "(?i)debit|credit|transaction|\\btxn|payment|paid|order|receipt|invoice|\\bbill|refund|spent|purchase|\\bupi\\b|alert|booking|ticket|subscription|renew|recharge|statement|e-?mandate|your trip",
    )

    fun defaultHost(address: String): String = when (address.substringAfter('@').lowercase()) {
        "gmail.com", "googlemail.com" -> "imap.gmail.com"
        "outlook.com", "hotmail.com", "live.com", "msn.com" -> "outlook.office365.com"
        "yahoo.com", "yahoo.co.in", "ymail.com" -> "imap.mail.yahoo.com"
        "icloud.com", "me.com" -> "imap.mail.me.com"
        "zoho.com", "zohomail.in" -> "imap.zoho.com"
        "rediffmail.com" -> "imap.rediffmail.com"
        else -> "imap." + address.substringAfter('@')
    }

    suspend fun testConnection(config: Config): kotlin.Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val store = session(config).getStore("imaps")
            store.connect(config.host, config.port, config.address, config.password)
            try {
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                inbox.messageCount.also { inbox.close(false) }
            } finally {
                store.close()
            }
        }
    }

    /** Reads new messages since the last sync (or the look-back window on a full rescan). */
    suspend fun sync(full: Boolean = false): Result = withContext(Dispatchers.IO) {
        val s = settings.current()
        if (!s.emailConfigured) return@withContext Result(error = "Email is not configured")
        val password = secrets.decrypt(s.emailPasswordEnc) ?: return@withContext Result(error = "Saved password could not be read; enter it again")
        val config = Config(s.emailHost, s.emailPort, s.emailAddress, password)
        val windowStart = System.currentTimeMillis() - s.emailLookbackDays * 86_400_000L
        val since = if (full) windowStart else maxOf(s.emailCursor, windowStart)
        val importId = imports.start(ImportKind.EMAIL_SYNC, s.emailAddress)
        var result = Result()
        var latest = since
        try {
            val store = session(config).getStore("imaps")
            store.connect(config.host, config.port, config.address, config.password)
            try {
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                // IMAP date search has day granularity; exact filtering happens below.
                val messages = inbox.search(ReceivedDateTerm(ComparisonTerm.GE, Date(since - 86_400_000L)))
                inbox.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) })
                for (m in messages) {
                    val received = m.receivedDate?.time ?: m.sentDate?.time ?: continue
                    if (received <= since) continue
                    latest = maxOf(latest, received)
                    val from = m.from?.firstOrNull()?.toString().orEmpty()
                    val subject = m.subject.orEmpty()
                    if (!financialHint.containsMatchIn("$from $subject")) continue
                    result = result.copy(checked = result.checked + 1)
                    val outcome = process(m, from, subject, received, importId)
                    result = count(result, outcome)
                }
                inbox.close(false)
            } finally {
                store.close()
            }
            settings.recordEmailSync(latest, System.currentTimeMillis(), null)
            imports.finish(importId, ImportRepository.Counts(total = result.checked, imported = result.created, duplicates = result.merged, skipped = result.ignored))
            if (result.created > 0) postIngest.checkBudgets()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Email sync failed", e)
            val message = friendlyError(e)
            settings.recordEmailSync(latest, System.currentTimeMillis(), message)
            imports.finish(importId, ImportRepository.Counts(total = result.checked, imported = result.created), error = message)
            result.copy(error = message)
        }
    }

    /** Imports a saved .eml file. Statement attachments are returned for the statement importer. */
    suspend fun importEml(uri: Uri, displayName: String?): Result = withContext(Dispatchers.IO) {
        val importId = imports.start(ImportKind.EML_FILE, displayName)
        try {
            val message = context.contentResolver.openInputStream(uri)?.use { MimeMessage(Session.getInstance(Properties()), it) }
                ?: throw IllegalArgumentException("Could not open the file")
            val from = message.from?.firstOrNull()?.toString().orEmpty()
            val received = message.receivedDate?.time ?: message.sentDate?.time ?: System.currentTimeMillis()
            val outcome = process(message, from, message.subject.orEmpty(), received, importId)
            val attachments = saveAttachments(message)
            val result = count(Result(checked = 1, attachments = attachments), outcome)
            imports.finish(
                importId,
                ImportRepository.Counts(total = 1, imported = result.created, duplicates = result.merged, skipped = result.ignored),
                error = if (outcome == null && attachments.isEmpty()) "No transaction found in this email" else null,
            )
            result
        } catch (e: Exception) {
            Log.e(TAG, "EML import failed", e)
            imports.finish(importId, ImportRepository.Counts(), error = e.message ?: "Unreadable email file")
            Result(error = e.message ?: "Unreadable email file")
        }
    }

    private suspend fun process(m: Message, from: String, subject: String, received: Long, importId: Long): Outcome? {
        val messageId = (m as? MimeMessage)?.messageID ?: "$from|$subject|$received"
        val hash = Hashing.sha256("email", messageId)
        if (ingestor.isProcessed(SourceType.EMAIL, hash)) return Outcome.AlreadyProcessed
        val body = bodyText(m).take(20_000)
        val item = TransactionIngestor.SourceItem(SourceType.EMAIL, hash, from, "$subject\n\n${HtmlText.toText(body).take(1500)}", received, importId)
        return when (val r = parser.parse(EmailParser.Email(from, subject, body, received))) {
            is MessageParseResult.Transaction -> ingestor.ingest(item, r.txn)
            is MessageParseResult.Unparsed -> { ingestor.recordUnparsed(item, r.reason, r.amountMinor); null }
            else -> null
        }
    }

    private fun count(result: Result, outcome: Outcome?): Result = when (outcome) {
        is Outcome.Created, is Outcome.FlaggedDuplicate -> result.copy(created = result.created + 1)
        is Outcome.Merged, Outcome.AlreadyProcessed -> result.copy(merged = result.merged + 1)
        null -> result.copy(ignored = result.ignored + 1)
    }

    private fun bodyText(part: Part): String {
        if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return ""
        return when {
            part.isMimeType("text/plain") -> part.content as? String ?: ""
            part.isMimeType("text/html") -> HtmlText.toText(part.content as? String ?: "")
            part.isMimeType("multipart/alternative") -> {
                val mp = part.content as Multipart
                val parts = (0 until mp.count).map { mp.getBodyPart(it) }
                (parts.firstOrNull { it.isMimeType("text/plain") } ?: parts.firstOrNull())?.let { bodyText(it) } ?: ""
            }
            part.isMimeType("multipart/*") -> {
                val mp = part.content as Multipart
                (0 until mp.count).joinToString("\n") { bodyText(mp.getBodyPart(it)) }
            }
            part.isMimeType("message/rfc822") -> (part.content as? Part)?.let { bodyText(it) } ?: ""
            else -> ""
        }
    }

    private fun saveAttachments(message: Message): List<File> {
        val out = ArrayList<File>()
        val dir = File(context.cacheDir, "email-attachments").apply { mkdirs() }
        fun walk(part: Part) {
            if (part.isMimeType("multipart/*")) {
                val mp = part.content as Multipart
                (0 until mp.count).forEach { walk(mp.getBodyPart(it)) }
                return
            }
            val name = part.fileName ?: return
            if (!Regex("(?i)\\.(pdf|csv|xlsx)$").containsMatchIn(name)) return
            val file = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            part.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            out += file
        }
        walk(message)
        return out
    }

    private fun session(config: Config): Session = Session.getInstance(
        Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.host)
            put("mail.imaps.port", config.port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
        },
    )

    private fun friendlyError(e: Exception): String {
        val text = (e.message ?: e.javaClass.simpleName)
        return when {
            text.contains("AUTHENTICATIONFAILED", true) || text.contains("Invalid credentials", true) || text.contains("authentication", true) ->
                "Login failed. For Gmail, create an App Password (Google Account → Security → 2-Step Verification → App passwords) and use it here."
            text.contains("UnknownHost", true) || e is java.net.UnknownHostException -> "No internet connection or wrong IMAP server"
            else -> text
        }
    }

    companion object {
        private const val TAG = "EmailImporter"
    }
}
