# Expense Tracker (Android)

An offline-first personal finance app for India. It records transactions automatically from bank SMS, payment-app notifications, email, bank statements and receipts, and keeps them in one de-duplicated history. You get dashboards, budgets, bill tracking and insights on top.

Kotlin · Jetpack Compose (Material 3) · MVVM + repositories · Room · Hilt · WorkManager · DataStore · PdfBox-Android · ML Kit OCR · JavaMail (IMAP).

All data stays on the phone. There are no accounts, servers or paid APIs.

---

## Install

The ready-made debug APK is at `app/build/outputs/apk/debug/app-debug.apk` (see *Build* to regenerate it).

1. Copy the APK to the phone, or run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Open it and allow installing from this source if Android asks.
3. **Android 13+ blocks some permissions for apps installed from an APK** ("restricted settings"). If SMS access or notification access can't be enabled, do this once:
   1. Open **Settings → Apps → Expense Tracker**.
   2. Tap **⋮** (top right) → **Allow restricted settings**.
   3. Grant the permission again from inside the app.

## Build

Requirements: JDK 17 and an Android SDK with platform 35 and build-tools 35.0.0. Set `sdk.dir` in `local.properties`.

```
gradlew.bat :app:assembleDebug          # APK -> app/build/outputs/apk/debug/
gradlew.bat :app:testDebugUnitTest      # 97 JVM tests: parsers, dedupe, finance maths + Robolectric/Room ingestion tests
```

On this machine the toolchain lives in `C:\Users\agniv\AndroidDev` (`jdk17`, `sdk`, `gradle-8.11.1`).

---

## What's implemented

### Automatic capture
| Source | How | Notes |
|---|---|---|
| **Bank / UPI / card SMS** | Inbox scan (1 month – 2 years, incremental) + real-time `SMS_RECEIVED` receiver + periodic catch-up worker | Layered parser (details below). OTPs, promotions, mandates, bill reminders, payment requests and personal SMS are ignored and not stored. |
| **Payment app notifications** | `NotificationListenerService` | Google Pay, PhonePe, Paytm, BHIM, Amazon Pay, CRED, MobiKwik and major bank apps. SMS-app notifications are used only if SMS permission is denied. |
| **Email** | IMAP over SSL (Gmail with an App Password, Outlook, Yahoo, iCloud, Zoho…) + `.eml` file import | Bank alerts are parsed like SMS. Merchant receipts are read from their totals. Statement attachments in `.eml` files can be imported. |
| **Bank statements** | PDF (text or scanned; password-protected supported), CSV, XLSX, statement photos (OCR), PhonePe statement PDF, Google Pay activity (Google Takeout HTML) | Header and column detection with a manual mapping editor, preview with duplicate badges, import history, reprocessing. |
| **Receipts** | Camera or gallery → on-device OCR → merchant, total and date | Save as a new transaction (auto-merges with the matching bank alert) or attach to an existing one. |
| **Share to app** | Share an SMS/email text, a statement file or a receipt photo into the app | Text goes through the parser preview; files open the importer. |
| **Manual entry** | + button | All fields: type, amount, merchant, category, account, method, date/time, status, reference, notes, tags. |

### SMS parser layers (`domain/parser`)
1. **Reject** non-transactions:
   - OTPs, offers (including TRAI `-P` promotional headers), mandate registrations, "will be debited" notices, bill and statement summaries, payment requests, refund-initiated notices
   - messages that merely mention an amount
2. **Bank templates** for HDFC, SBI, ICICI, Axis and Kotak alert formats.
3. **Generic extraction**:
   - amount, ignoring balance and limit amounts
   - debit/credit direction from verbs, after removing future and conditional clauses
   - status: completed, pending, failed or declined
   - masked account last-4 for your account and the counterparty
   - UPI and other references, merchant/payee and UPI ID
   - the date written in the message (used for late alerts) and time
4. **Merchant normalisation**:
   - about 230 Indian merchants, including food delivery, quick commerce, fuel, FASTag, IRCTC/airlines, electricity boards, telecom, OTT, pharmacies, brokers and insurers
   - payment gateways are stripped (`Razorpay*Swiggy` → Swiggy)
   - person-vs-business detection, which drives *Personal Transfers*
5. **Classification**: expense, income, refund, self transfer, card bill payment, investment, cash withdrawal, loan or reimbursement, plus hints (salary, interest, EMI).
6. **Confidence score**. Results below 60% go to the **Review** queue. Financial-looking messages that can't be parsed are kept under *Unrecognised* so you can add them by hand.

### One transaction history and the accuracy rules
- Every source writes through `TransactionIngestor`. Each raw item becomes a *source record* with a unique content hash, so rescans and re-imports are idempotent.
- **Duplicate detection** (`DuplicateDetector`) is explainable:
  - Inputs: reference number, amount, direction, a time window that depends on the sources, account, and merchant similarity.
  - Amount alone never merges.
  - Two alerts from the same channel only merge automatically when their references match; otherwise the newer one is flagged as a *possible duplicate* for you to resolve.
  - Merges are shown on the transaction ("Merged: same reference number…") and can be undone ("Separate").
- **Accuracy rules** (`FinanceCalculator`):
  - Only completed transactions count.
  - Spending = expenses − refunds − reimbursements.
  - Self transfers, credit-card bill payments, cash withdrawals, investments and loan proceeds are excluded from both spending and income.
  - Refunds are linked to the original payment and inherit its category. A refund of a *failed* payment is marked *reversed* and not counted.
  - A debit and a credit of the same amount on two of your accounts within 30 minutes become a self transfer (sent to review).
  - Every automatic decision can be overridden.
- **Categorisation** order: your rules → learned rules (created after you recategorise a merchant twice) → fixed categories for non-spending types → message hints → merchant dictionary → keywords → person/merchant fallbacks. Each transaction shows why it got its category.

### Screens
- **Home**:
  - greeting and period chips (today, week, month, last month, year, custom)
  - spend / income / savings hero card
  - category donut, daily or monthly spending trend
  - budget status, insight cards, upcoming bills, top merchants, recent transactions
  - banners for pending reviews and for missing SMS permission
- **Transactions**:
  - search (merchant, note, tag, reference, amount) and filters (date presets and custom range, category, account, payment method, source, type, amount range, hide failed)
  - four sort orders, grouping by day with daily totals
  - multi-select bulk recategorise, mark as self transfer, approve or delete
- **Transaction detail**:
  - recategorise with "apply to all from this merchant" and "always use this category"
  - change type or status, split across categories, notes and tags
  - receipt (camera or gallery)
  - linked refund, merchant history
  - source records with the original SMS/email text, merge reasons and *Separate*
- **Analytics**:
  - summary including excluded flows, category donut and list with change vs the previous period (tap to drill down)
  - income vs spending bars (6 or 12 months), trend line
  - top merchants, payment methods, accounts, income sources, weekday pattern
- **Budgets** (Plan tab):
  - overall and per-category monthly budgets with rollover, projection and alerts at 50/80/100%/exceeded
  - **Bills & subscriptions**: detected weekly/monthly/quarterly/yearly payments, confirm or dismiss, reminders, amount-change detection, monthly total. Expected dates are predictions only.
  - **Goals**: savings goals with contributions and a monthly target.
- **Settings**:
  - SMS scan with progress, notification capture, email, statement import, receipt scan, parser test bench
  - review queue, import history
  - accounts (auto-detected from alerts; opening balance; computed vs bank-reported balance), categories, rules
  - notification toggles, theme (light/dark/system, 7 accents, Material You)
  - backup/restore, CSV and PDF reports, the assistant
- **Ask**: local answers to "How much did I spend on Swiggy last month?", "Compare this month with last month", "Show my subscriptions" and similar. No network or API key. `FinanceQueryEngine` is the extension point for a future LLM.
- **Onboarding**: welcome → how it works → SMS permission and first scan → detected accounts → dashboard. Steps can be skipped.

### Background work
| Job | Schedule |
|---|---|
| Real-time SMS receiver | `goAsync`; falls back to a scan job on failure |
| Incremental SMS scan | Every 6 h and on app open (safety net) |
| Email sync | Every 6 h when connected |
| Maintenance (recurring detection, bill reminders, budget checks) | Daily |

Android can defer periodic work under Doze or battery saver. Real-time capture relies on the SMS broadcast and the notification listener.

---

## How to test

### SMS ingestion
- **On an emulator**, the app records these as they arrive:
  ```
  adb emu sms send VM-HDFCBK "Rs 450 debited from A/c XX4521 through UPI to SWIGGY on 04-11-2026. Ref 629384751102."
  adb emu sms send AD-ICICIT "INR 1,250.00 spent using ICICI Bank Card XX1234 on 04-Nov-26 on AMAZON. Avl Limit: INR 45,000.00."
  ```
  Send the first message twice: the second copy is merged, not duplicated.
- **On a phone**: grant SMS access, then **Settings → Scan SMS now**. To check a single message, use **Settings → Test the SMS parser**: paste any text and see exactly what would be recorded, then save it.
- **Unit tests**: `SmsParserTest` covers debit/credit/UPI/card/ATM/failed/declined/pending/refund/reversal/OTP/promo/mandate/bill/request/self-transfer/card-bill/salary/unknown-bank/late-alert cases with synthetic messages.

### Statement import
**Settings → Import bank statement**, then pick a PDF/CSV/XLSX/image. If the PDF is protected, enter its password (not stored). In the preview:
- check or adjust the column mapping (CSV/XLSX)
- rows already recorded from SMS show *Matches existing – will merge*
- unreadable lines are listed

After import, **Import history** shows counts and offers *Reprocess*. Synthetic CSV, PDF-text, credit-card, PhonePe, Google Pay and XLSX cases are in `StatementParserTest`.

### Email
**Settings → Email receipts and alerts.** For Gmail:
1. Turn on 2-Step Verification.
2. Create an App Password (Google Account → Security → App passwords).
3. Enter it with server `imap.gmail.com:993`, then **Test connection & save → Sync now**.

Without a mailbox, import a downloaded `.eml` file.

---

## Needs configuration / limitations
- **Gmail API is not used.** It needs a Google Cloud OAuth client registered for this app's signing key. IMAP with an App Password is the working alternative and supports any mailbox provider.
- **Payment apps don't expose their history.** Google Pay / PhonePe / Paytm transactions are captured from bank SMS, their notifications, or their statement exports (PhonePe PDF, Google Takeout for Google Pay).
- **Restricted settings** (Android 13+): see *Install*.
- **OCR quality** depends on the photo or scan. OCR imports are labelled and should be checked.
- **Legacy `.xls` files** are not supported; re-save as `.xlsx` or `.csv`.
- **EMI principal/interest split** isn't detected automatically from statements. Use *Split* on a transaction to separate them.
- **Account Aggregator (RBI AA framework)** imports are not possible for a personal app without a licensed FIU.
- **Ingestion tests** (`IngestionTest`) run the real pipeline on an in-memory Room database under Robolectric:
  - idempotent rescans
  - SMS + notification and SMS + statement merges
  - refunds and reversals of failed payments
  - self transfers and transfer pairing
  - learned categories, undoing merges, resolving possible duplicates

  UI flows are not covered by automated tests. They were checked by hand on an Android 14 emulator:
  - onboarding, live SMS capture, inbox scan
  - dashboard, transaction list and detail, manual add
  - CSV statement import with SMS merging
  - budgets, settings, the assistant

  Not run on the emulator: the notification listener, IMAP email sync and receipt OCR (their parsers are unit-tested).

## Project layout
```
app/src/main/java/com/expensetracker/app/
  core/            Money (paise, Indian grouping), hashing
  domain/model     enums, default categories, date ranges
  domain/parser    SMS / notification / email parsers, merchant directory & normaliser, dates, amounts
  domain/statement CSV & XLSX readers, tabular & text statement parsers, narration parser, receipt OCR parsing
  domain/engine    categoriser, duplicate detector, finance calculator, budgets, recurring, insights, query engine
  data/db          Room entities & DAOs     data/repo  repositories     data/ingest  TransactionIngestor, PostIngest
  data/prefs       DataStore settings, Keystore secret store
  sources/         SMS reader/receiver/importer, notification listener, IMAP/EML, statements (PdfBox, ML Kit), backup, export
  work/            WorkManager workers & scheduler      notify/  notification channels
  ui/              Compose theme, components (charts), navigation, screens
app/src/test/      JVM unit tests (synthetic data only)
```
