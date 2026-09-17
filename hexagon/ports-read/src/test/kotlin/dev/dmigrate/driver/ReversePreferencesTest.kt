package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Die deklarierten Lese-Praeferenzen eines Laufs, angewendet je gelesenem
 * Dialekt (`spec/dialect-preference-mechanism.md`).
 */
class ReversePreferencesTest : FunSpec({

    test("without a declaration every read keeps the conservative default") {
        val options = ReversePreferences().applyTo(SchemaReadOptions(), DatabaseDialect.MYSQL)
        options shouldBe SchemaReadOptions()
        options.autoIncrementSyntax shouldBe AutoIncrementSyntaxReverse.SERIAL
        options.sqliteAutoincrement shouldBe SqliteAutoincrementReverse.IDENTIFIER
    }

    test("the syntax preference applies only to the dialect it was declared for") {
        val preferences = ReversePreferences(
            autoIncrementSyntax = mapOf(DatabaseDialect.MYSQL to AutoIncrementSyntaxReverse.IDENTITY),
        )
        preferences.applyTo(SchemaReadOptions(), DatabaseDialect.MYSQL).autoIncrementSyntax shouldBe
            AutoIncrementSyntaxReverse.IDENTITY
        preferences.applyTo(SchemaReadOptions(), DatabaseDialect.SQLITE).autoIncrementSyntax shouldBe
            AutoIncrementSyntaxReverse.SERIAL
        preferences.autoIncrementSyntaxFor(DatabaseDialect.POSTGRESQL) shouldBe AutoIncrementSyntaxReverse.SERIAL
    }

    test("the other read options survive, and the width preference is carried along") {
        val base = SchemaReadOptions(includeViews = false, includeTriggers = false)
        val applied = ReversePreferences(sqliteAutoincrement = SqliteAutoincrementReverse.BIGINTEGER_IDENTITY)
            .applyTo(base, DatabaseDialect.SQLITE)
        applied.includeViews shouldBe false
        applied.includeTriggers shouldBe false
        applied.includeFunctions shouldBe true
        applied.sqliteAutoincrement shouldBe SqliteAutoincrementReverse.BIGINTEGER_IDENTITY
    }

    test("where a preference was declared travels with it, per dialect") {
        val preferences = ReversePreferences(
            sqliteAutoincrement = SqliteAutoincrementReverse.BIGINTEGER_IDENTITY,
            autoIncrementSyntax = mapOf(
                DatabaseDialect.MYSQL to AutoIncrementSyntaxReverse.IDENTITY,
                DatabaseDialect.SQLITE to AutoIncrementSyntaxReverse.IDENTITY,
            ),
            sqliteAutoincrementSource = PreferenceSource.FLAG,
            autoIncrementSyntaxSources = mapOf(DatabaseDialect.MYSQL to PreferenceSource.FLAG),
        )
        val mysql = preferences.applyTo(SchemaReadOptions(), DatabaseDialect.MYSQL)
        mysql.autoIncrementSyntaxSource shouldBe PreferenceSource.FLAG
        mysql.sqliteAutoincrementSource shouldBe PreferenceSource.FLAG
        preferences.applyTo(SchemaReadOptions(), DatabaseDialect.SQLITE).autoIncrementSyntaxSource shouldBe
            PreferenceSource.CONFIG
        ReversePreferences().applyTo(SchemaReadOptions(), DatabaseDialect.MYSQL).autoIncrementSyntaxSource shouldBe
            PreferenceSource.CONFIG
    }
})
