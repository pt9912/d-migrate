package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * 0.9.7 SQLite-Sequence-Emulation Phase B.1: pins the
 * [SqliteNamedSequenceMode] enum surface and its slot in
 * [DdlDialectContext.Sqlite]. Strukturell parallel zu den
 * [MysqlNamedSequenceMode] tests in [PortsReadTest]; eigene Datei,
 * damit der SQLite-seitige Surface wachsen kann, ohne [PortsReadTest]
 * Richtung Detekt `LargeClass` zu schieben.
 *
 * Das Enum traegt die gleichen CLI-Werte wie das MySQL-Pendant
 * (`action_required`, `helper_table`), ist aber ein eigener Typ —
 * ein `--mysql-named-sequences`-Wert kann nicht in einen SQLite-
 * Target binden und umgekehrt (Runner-seitige Dialekt-Validierung
 * in `SchemaGenerateRunner.resolveSqliteSeqMode`).
 */
class SqliteNamedSequenceModeTest : FunSpec({

    test("SqliteNamedSequenceMode has two values, in stable order") {
        SqliteNamedSequenceMode.entries.map { it.name } shouldBe
            listOf("ACTION_REQUIRED", "HELPER_TABLE")
    }

    test("SqliteNamedSequenceMode.fromCliName resolves known names") {
        SqliteNamedSequenceMode.fromCliName("action_required") shouldBe
            SqliteNamedSequenceMode.ACTION_REQUIRED
        SqliteNamedSequenceMode.fromCliName("helper_table") shouldBe
            SqliteNamedSequenceMode.HELPER_TABLE
    }

    test("SqliteNamedSequenceMode.fromCliName is case-insensitive") {
        SqliteNamedSequenceMode.fromCliName("HELPER_TABLE") shouldBe
            SqliteNamedSequenceMode.HELPER_TABLE
        SqliteNamedSequenceMode.fromCliName("Action_Required") shouldBe
            SqliteNamedSequenceMode.ACTION_REQUIRED
    }

    test("SqliteNamedSequenceMode.fromCliName returns null for unknown values") {
        SqliteNamedSequenceMode.fromCliName("auto") shouldBe null
        SqliteNamedSequenceMode.fromCliName("") shouldBe null
    }

    test("SqliteNamedSequenceMode.cliName matches the CLI surface") {
        SqliteNamedSequenceMode.ACTION_REQUIRED.cliName shouldBe "action_required"
        SqliteNamedSequenceMode.HELPER_TABLE.cliName shouldBe "helper_table"
    }

    test("SqliteNamedSequenceMode valueOf round-trips") {
        SqliteNamedSequenceMode.valueOf("ACTION_REQUIRED") shouldBe
            SqliteNamedSequenceMode.ACTION_REQUIRED
        SqliteNamedSequenceMode.valueOf("HELPER_TABLE") shouldBe
            SqliteNamedSequenceMode.HELPER_TABLE
    }

    test("SqliteNamedSequenceMode toString contains name") {
        SqliteNamedSequenceMode.ACTION_REQUIRED.toString() shouldContain "ACTION_REQUIRED"
        SqliteNamedSequenceMode.HELPER_TABLE.toString() shouldContain "HELPER_TABLE"
    }

    test("DdlDialectContext.Sqlite defaults namedSequenceMode to ACTION_REQUIRED") {
        DdlDialectContext.Sqlite().namedSequenceMode shouldBe
            SqliteNamedSequenceMode.ACTION_REQUIRED
    }

    test("DdlDialectContext.Sqlite carries an explicit HELPER_TABLE mode") {
        val ctx = DdlDialectContext.Sqlite(namedSequenceMode = SqliteNamedSequenceMode.HELPER_TABLE)
        ctx.namedSequenceMode shouldBe SqliteNamedSequenceMode.HELPER_TABLE
    }

    test("DdlGenerationOptions.sqliteContext exposes the namedSequenceMode") {
        val opts = DdlGenerationOptions(
            dialectContext = DdlDialectContext.Sqlite(
                namedSequenceMode = SqliteNamedSequenceMode.HELPER_TABLE,
            ),
        )
        opts.sqliteContext?.namedSequenceMode shouldBe SqliteNamedSequenceMode.HELPER_TABLE

        // Default (None) context has no sqlite slot.
        DdlGenerationOptions().sqliteContext shouldBe null
    }
})
