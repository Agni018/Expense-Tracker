# Expense Tracker — Progress

Last updated: 2026-11-06.

**Status: working debug build.** `app/build/outputs/apk/debug/app-debug.apk` (about 73 MB) contains the full app. 97 tests pass (83 pure-logic unit tests and 14 Robolectric + Room ingestion tests). The app was smoke-tested on an Android 14 emulator. See README.md for features, install steps, how to test, and limitations.

## Environment
| Item | Location |
|---|---|
| JDK 17 | `C:\Users\agniv\AndroidDev\jdk17` |
| Android SDK (platform 35, build-tools 35.0.0, platform-tools, emulator, `android-34;google_apis;x86_64` image) | `C:\Users\agniv\AndroidDev\sdk` |
| Gradle 8.11.1 | `C:\Users\agniv\AndroidDev\gradle-8.11.1` (wrapper also in repo) |
| Test AVD `smoke` | `C:\Users\agniv\AndroidDev\avd` |

Build (cmd.exe):
```
set JAVA_HOME=C:\Users\agniv\AndroidDev\jdk17
cd /d "C:\Users\agniv\Desktop\PROJECTS\Expense Tracker"
C:\Users\agniv\AndroidDev\gradle-8.11.1\bin\gradle.bat :app:assembleDebug :app:testDebugUnitTest
```

## Done
- **Domain (pure Kotlin, unit tested)**:
  - SMS, notification and email parsers, bank templates, merchant directory and normaliser, dates and amounts
  - CSV, XLSX, PDF-text and OCR statement parsers; PhonePe and Google Pay exports; receipt parser
  - categoriser with rules and learning, duplicate detector, finance calculator, budgets, recurring detection, insights, local query engine
- **Data**:
  - Room (13 tables), repositories
  - `TransactionIngestor`: idempotent, deduplicates and merges, auto-detects accounts, links refunds, pairs self transfers, updates balances
  - `PostIngest` (notifications, budget alerts), DataStore settings, Keystore-encrypted email password
- **Sources**:
  - SMS reader, live receiver and importer (the scan skips messages the receiver already handled)
  - notification listener, IMAP and `.eml` import
  - statement importer (PdfBox with password support, ML Kit OCR fallback, preview, history, reprocess)
  - backup/restore, CSV and PDF reports
- **Background**: WorkManager workers (SMS scan, email sync, daily maintenance) and the scheduler.
- **UI**: every screen listed in the README, the theme system, Canvas charts, onboarding, share-to-app intents.

## Emulator smoke test (2026-11-06)
Steps:
1. Sent 7 test bank SMS with `adb emu sms send`, including a duplicate and an OTP.
2. Live capture recorded them; the OTP was ignored and the duplicate merged.
3. The inbox scan imported the same history on a fresh install.
4. Dashboard totals and categories were correct.
5. Manual add worked, including the category suggestion.
6. CSV statement import: 2 rows added, 2 merged with the SMS entries; income was not double-counted.
7. Budgets, settings and the assistant worked. No crashes.

Fixes made from what the smoke test showed:
- sender headers without a hyphen (`VMHDFCBK`)
- salary payee cut at " - "
- scan writing redundant records for messages the receiver already handled
- tab label truncation, scan-window chip wrapping, the search placeholder
- manual merchant name being overwritten
- content showing under the status bar

## Changes (2026-11-10)
- **Transaction filters**: a search box at the top narrows the options (a matching section title keeps all its options); Reset and Apply slide up, pinned at the bottom, once anything changes or filters are already active; Categories has a **+ New** button. All / Expenses / Income / Not spending are now chips under "Show" (the segmented row cut "Not spending" down to "Not").
- **Categories**: the add/edit dialog is shared (`ui/components/CategoryDialog.kt`); the emoji is optional (type any emoji or pick one; none saves as 🏷️); duplicate names are blocked.
- **Donut charts (Home, Analytics)**: tap a slice to see its total, then tap the centre ("View ›") to open that category's transactions for the period, newest first.
- Focusing a text field in the filter sheet used to focus the Transactions search box too, so the keyboard reopened after the sheet closed; that box can no longer take focus while the sheet is open.

## Known limitations (also in README)
- Gmail API is not used (needs a Google Cloud OAuth client); IMAP with an App Password is used instead.
- Legacy `.xls` files are not supported; EMI principal/interest are not split automatically.
- No automated UI tests. The notification listener, IMAP sync and receipt OCR were not exercised on the emulator; their parsers are unit-tested.

## Next steps (optional)
- Try the APK on a real phone and use Settings → Test the SMS parser with your bank's real messages; add bank templates for any formats it misreads.
- Compose UI tests for the main flows.
- Release signing config if the app is ever distributed beyond personal use.
