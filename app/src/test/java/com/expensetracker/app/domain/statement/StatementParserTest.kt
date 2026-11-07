package com.expensetracker.app.domain.statement

import com.expensetracker.app.IST
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StatementParserTest {
    private val ref = LocalDate.of(2026, 11, 6)

    private val hdfcCsv = """
        HDFC BANK Ltd.,,,,,,
        Account No : XXXXXXXX4521,,,,,,
        Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
        01/11/26,UPI-SWIGGY-SWIGGY@ICICI-ICIC0DC0099-629384751102-UPI,0000629384751102,01/11/26,450.00,,"24,550.00"
        02/11/26,NEFT CR-CITI0000001-ACME CORP-SALARY OCT-CITIN26123456,CITIN26123456,02/11/26,,"85,000.00","1,09,550.00"
        03/11/26,ATW-512345XXXXXX1234-S1AW000123-BANGALORE,0000000123,03/11/26,"2,000.00",,"1,07,550.00"
        ,,,,,,
        STATEMENT SUMMARY,,,,,,
    """.trimIndent()

    @Test
    fun `hdfc style csv with header detection`() {
        val rows = CsvReader.parse(hdfcCsv)
        val result = TabularStatementParser.parse(rows, reference = ref)
        assertEquals(2, result.mapping!!.headerRow)
        assertEquals(3, result.rows.size)
        assertTrue(result.failed.isEmpty())
        val (swiggy, salary, atm) = result.rows
        assertEquals(LocalDate.of(2026, 11, 1), swiggy.date)
        assertEquals(45000L, swiggy.amountMinor)
        assertEquals(Direction.DEBIT, swiggy.direction)
        assertEquals(2455000L, swiggy.balanceMinor)
        assertEquals(Direction.CREDIT, salary.direction)
        assertEquals(8500000L, salary.amountMinor)
        assertEquals(200000L, atm.amountMinor)

        val parsed = NarrationParser.toParsed(swiggy, false, "4521", "HDFC Bank", IST, "CSV")
        assertEquals("Swiggy", parsed.merchant)
        assertEquals("629384751102", parsed.reference)
        assertEquals(TxnType.INCOME, NarrationParser.toParsed(salary, false, "4521", null, IST, "CSV").type)
        assertEquals(TxnType.CASH_WITHDRAWAL, NarrationParser.toParsed(atm, false, "4521", null, IST, "CSV").type)
    }

    @Test
    fun `single amount column with dr cr indicator and dd-mmm-yyyy`() {
        val csv = """
            Txn Date;Description;Amount;Dr/Cr;Balance
            04-Nov-2026;POS 512345XXXXXX1234 AMAZON PAY IN;1299.00;DR;8701.00
            05-Nov-2026;IMPS-629384751120-RAHUL SHARMA;500.00;CR;9201.00
        """.trimIndent()
        val result = TabularStatementParser.parse(CsvReader.parse(csv), reference = ref)
        assertEquals(2, result.rows.size)
        assertEquals(Direction.DEBIT, result.rows[0].direction)
        assertEquals(Direction.CREDIT, result.rows[1].direction)
        assertEquals("Amazon", NarrationParser.toParsed(result.rows[0], false, null, null, IST, "CSV").merchant)
    }

    @Test
    fun `month first dates are detected from the column`() {
        val csv = "Date,Description,Amount\n11/25/2026,Coffee,-120.00\n11/26/2026,Refund,50.00\n"
        val result = TabularStatementParser.parse(CsvReader.parse(csv), reference = LocalDate.of(2026, 11, 30))
        assertFalse(result.mapping!!.dayFirst)
        assertEquals(LocalDate.of(2026, 11, 25), result.rows[0].date)
        assertEquals(Direction.DEBIT, result.rows[0].direction)
        assertEquals(Direction.CREDIT, result.rows[1].direction)
    }

    @Test
    fun `zero transaction import`() {
        val result = TabularStatementParser.parse(CsvReader.parse("Date,Narration,Withdrawal,Deposit,Balance\n"), reference = ref)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `corrupted input reports failure`() {
        val result = TabularStatementParser.parse(CsvReader.parse("\u0000\u0001garbage%%%\nmore garbage"), reference = ref)
        assertTrue(result.rows.isEmpty())
        assertTrue(result.failed.isNotEmpty())
    }

    @Test
    fun `bad rows are reported not dropped`() {
        val csv = "Date,Narration,Withdrawal,Deposit\n01/11/26,Swiggy,450.00,\n??/11/26,Zomato,250.00,\n02/11/26,Uber,abc,\n"
        val result = TabularStatementParser.parse(CsvReader.parse(csv), reference = ref)
        assertEquals(1, result.rows.size)
        assertEquals(2, result.failed.size)
    }

    @Test
    fun `pdf text statement direction from running balance`() {
        val lines = listOf(
            "Statement of Account",
            "Date Narration Withdrawal Deposit Balance",
            "Opening Balance 10,000.00",
            "01/11/2026 UPI/DR/629384751102/SWIGGY/YESB/swiggy@yesbank 450.00 9,550.00",
            "02/11/2026 BY TRANSFER-NEFT*ACME CORP SALARY 85,000.00 94,550.00",
            "03/11/2026 POS 512345XXXXXX1234 AMAZON PAY IN 1,299.00 93,251.00",
            "Page 1 of 1",
        )
        val result = TextStatementParser.parse(lines, ref)
        assertEquals(3, result.rows.size)
        assertEquals(listOf(Direction.DEBIT, Direction.CREDIT, Direction.DEBIT), result.rows.map { it.direction })
        assertTrue(result.rows.all { it.directionCertain })
        assertEquals(8500000L, result.rows[1].amountMinor)
    }

    @Test
    fun `credit card statement text`() {
        val lines = listOf(
            "HDFC Bank Credit Card Statement",
            "Payment Due Date 20/11/2026 Total Amount Due 3,749.00 Minimum Amount Due 190.00",
            "04/10/2026 SWIGGY BANGALORE 450.00",
            "10/10/2026 PAYMENT RECEIVED - THANK YOU 5,000.00 Cr",
            "15/10/2026 AMAZON PAY INDIA 3,299.00",
        )
        val result = TextStatementParser.parse(lines, ref)
        assertTrue(result.isCreditCardStatement)
        assertEquals(3, result.rows.size)
        assertEquals(Direction.CREDIT, result.rows[1].direction)
        assertEquals(TxnType.CREDIT_CARD_PAYMENT, NarrationParser.toParsed(result.rows[1], true, "1234", null, IST, "PDF").type)
        assertEquals(TxnType.EXPENSE, NarrationParser.toParsed(result.rows[0], true, "1234", null, IST, "PDF").type)
    }

    @Test
    fun `phonepe statement`() {
        val lines = listOf(
            "Transaction Statement for 98XXXXX210",
            "Date Transaction Details Type Amount",
            "Nov 04, 2026 Paid to Swiggy DEBIT ₹450",
            "10:15 am Transaction ID T2611041015123456789",
            "UTR No. 629384751102",
            "Paid by XXXXXX4521",
            "Nov 05, 2026 Received from Rahul Sharma CREDIT ₹500",
            "08:00 pm Transaction ID T2611052000123456789",
        )
        val result = TextStatementParser.parse(lines, ref)
        assertEquals("PhonePe statement", result.format)
        assertEquals(2, result.rows.size)
        assertEquals("4521", result.rows[0].accountHint)
        assertEquals("629384751102", result.rows[0].reference)
        assertEquals(Direction.CREDIT, result.rows[1].direction)
    }

    @Test
    fun `google pay takeout activity`() {
        val lines = listOf(
            "Google Pay",
            "Paid ₹450.00 to Swiggy using Bank Account XXXXXX4521",
            "Nov 4, 2026, 10:15:12 AM GMT+05:30",
            "Completed",
            "Google Pay",
            "Sent ₹200.00 to Rahul Sharma using Bank Account XXXXXX4521",
            "Nov 5, 2026, 8:00:00 PM GMT+05:30",
            "Failed",
        )
        val result = TextStatementParser.parse(lines, ref)
        assertEquals("Google Pay activity", result.format)
        assertEquals(1, result.rows.size)
        assertEquals(1, result.failed.size)
        assertEquals("4521", result.rows[0].accountHint)
    }

    @Test
    fun `xlsx reader with shared strings and serial dates`() {
        val bytes = xlsx(
            sharedStrings = listOf("Date", "Narration", "Withdrawal", "Deposit", "Swiggy order"),
            sheet = """
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>
                <row r="2"><c r="A2" s="1"><v>46330</v></c><c r="B2" t="s"><v>4</v></c><c r="C2"><v>450.10000000000002</v></c></row>
            """.trimIndent(),
        )
        val rows = XlsxReader.read(ByteArrayInputStream(bytes))
        assertEquals(listOf("2026-11-04", "Swiggy order", "450.1"), rows[1])
        val result = TabularStatementParser.parse(rows, reference = ref)
        assertEquals(45010L, result.rows.single().amountMinor)
    }

    private fun xlsx(sharedStrings: List<String>, sheet: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""")
            put("xl/styles.xml", """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>""")
            put("xl/sharedStrings.xml", "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" + sharedStrings.joinToString("") { "<si><t>$it</t></si>" } + "</sst>")
            put("xl/worksheets/sheet1.xml", """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sheet</sheetData></worksheet>""")
        }
        return out.toByteArray()
    }

    @Test
    fun `receipt ocr text`() {
        val receipt = ReceiptParser.parse(
            listOf("SRI SAI MEDICALS", "GSTIN 29ABCDE1234F1Z5", "Date: 04/11/2026", "Paracetamol 2 x 30.00 60.00", "Sub Total 60.00", "CGST 1.50", "Grand Total 63.00", "Thank you"),
            ref,
        )
        assertEquals("Sri Sai Medicals", receipt.merchant)
        assertEquals(6300L, receipt.amountMinor)
        assertEquals(LocalDate.of(2026, 11, 4), receipt.date)
    }

    @Test
    fun `ocr boxes regroup into rows`() {
        val boxes = listOf(
            OcrLayout.Box("450.00", 400, 102, 460, 118),
            OcrLayout.Box("01/11/2026", 10, 100, 90, 120),
            OcrLayout.Box("SWIGGY", 100, 101, 180, 119),
            OcrLayout.Box("02/11/2026", 10, 140, 90, 160),
        )
        assertEquals(listOf("01/11/2026 SWIGGY 450.00", "02/11/2026"), OcrLayout.toLines(boxes))
    }
}
