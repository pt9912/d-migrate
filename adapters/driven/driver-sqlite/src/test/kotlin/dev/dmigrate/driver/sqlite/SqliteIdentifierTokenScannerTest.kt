package dev.dmigrate.driver.sqlite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 0.9.7 SQLite-Sequence Phase D unit coverage for
 * [SqliteIdentifierTokenScanner] — token-based body matching used
 * by the W120 / secondary-match path (Plan §6.1 lines 1716–1726).
 */
class SqliteIdentifierTokenScannerTest : FunSpec({

    test("plain unquoted identifier at a word boundary is recognised") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "UPDATE dmg_sequences SET next_value = 5",
            "dmg_sequences",
        ) shouldBe true
    }

    test("double-quoted identifier is recognised") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "UPDATE \"dmg_sequences\" SET",
            "dmg_sequences",
        ) shouldBe true
    }

    test("backtick-quoted identifier is recognised") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "UPDATE `dmg_sequences` SET",
            "dmg_sequences",
        ) shouldBe true
    }

    test("bracket-quoted identifier is recognised") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "UPDATE [dmg_sequences] SET",
            "dmg_sequences",
        ) shouldBe true
    }

    test("schema-qualified quoted identifier is recognised") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "UPDATE main.\"dmg_sequences\" SET",
            "dmg_sequences",
        ) shouldBe true
    }

    test("case-insensitive matching") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "update DMG_SEQUENCES set",
            "dmg_sequences",
        ) shouldBe true
    }

    test("substring inside a longer identifier is NOT a match") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "UPDATE my_dmg_sequences_backup SET",
            "dmg_sequences",
        ) shouldBe false
    }

    test("occurrence inside a string literal does not count") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "INSERT INTO log VALUES('dmg_sequences was updated')",
            "dmg_sequences",
        ) shouldBe false
    }

    test("occurrence inside a line comment does not count") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "SELECT 1 -- dmg_sequences is the helper\n",
            "dmg_sequences",
        ) shouldBe false
    }

    test("occurrence inside a block comment does not count") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "SELECT 1 /* dmg_sequences */ FROM other",
            "dmg_sequences",
        ) shouldBe false
    }

    test("ROWID identifier check used by _ai body integrity") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "UPDATE \"orders\" SET \"x\" = … WHERE ROWID = NEW.ROWID",
            "ROWID",
        ) shouldBe true
    }

    test("table name with embedded underscores must still match exactly") {
        SqliteIdentifierTokenScanner.containsIdentifier(
            "UPDATE \"my_orders\" SET",
            "my_orders",
        ) shouldBe true
    }
})
