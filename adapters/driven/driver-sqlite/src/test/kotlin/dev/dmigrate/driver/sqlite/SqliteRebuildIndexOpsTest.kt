package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TableMetadata
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationStreamClassifier
import dev.dmigrate.driver.migration.TransactionScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Index-Operationen einer Neubau-Tabelle (Issue #60): der Neubau legt die Indizes der Zielsicht selbst an,
 * ein Index der Quellsicht verschwindet mit `DROP TABLE`. Die Operationen duerfen weder ein zweites `CREATE INDEX`
 * noch ein `DROP INDEX` nach dem Neubau erzeugen und keine `RUNNER_OWNED`-Anweisung neben den Strom setzen.
 */
class SqliteRebuildIndexOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    fun ix(name: String, column: String, unique: Boolean = false, type: IndexType = IndexType.BTREE, where: String? = null) =
        IndexDefinition(name = name, columns = listOf(IndexColumn(column)), type = type, unique = unique, where = where)

    fun table(
        aRequired: Boolean = false,
        uUnique: Boolean = false,
        indices: List<IndexDefinition> = emptyList(),
        extra: Map<String, ColumnDefinition> = emptyMap(),
    ) = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "u" to ColumnDefinition(NeutralType.Integer, unique = uUnique),
            "a" to ColumnDefinition(NeutralType.Text(), required = aRequired),
            "b" to ColumnDefinition(NeutralType.Text()),
        ) + extra,
        primaryKey = listOf("id"),
        indices = indices,
    )

    fun schema(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables.toMap())

    fun render(current: SchemaDefinition, desired: SchemaDefinition, up: Boolean): MigrationDdlResult {
        val diff = planner.plan(current, desired, SchemaComparator().compare(current, desired))
        return if (up) gen.generateUp(diff, DdlGenerationOptions()) else gen.generateDown(diff, DdlGenerationOptions())
    }

    fun MigrationDdlResult.sqls() = statements.map { it.sql }

    fun List<String>.count(prefix: String) = count { it.startsWith(prefix) }

    test("Datei-unique gegen eindeutigen Index: UP baut neu, ohne DROP INDEX, ohne RUNNER_OWNED-Anweisung") {
        val current = schema("t" to table(indices = listOf(ix("ix_u", "u", unique = true))))
        val desired = schema("t" to table(uUnique = true))

        val r = render(current, desired, up = true)

        r.isBlocked shouldBe false
        val sqls = r.sqls()
        sqls.count("DROP INDEX") shouldBe 0
        sqls.count("CREATE INDEX") + sqls.count("CREATE UNIQUE INDEX") shouldBe 0
        sqls.single { it.startsWith("CREATE TABLE") } shouldContain "UNIQUE"
        r.statements.all { it.transactionScope == TransactionScope.STREAM_OWNED } shouldBe true
        MigrationStreamClassifier.unsupportedTransactionScopeReason(r.statements).shouldBeNull()
    }

    test("Datei-unique gegen eindeutigen Index: DOWN stellt den Index genau einmal wieder her") {
        val current = schema("t" to table(indices = listOf(ix("ix_u", "u", unique = true))))
        val desired = schema("t" to table(uUnique = true))

        val r = render(current, desired, up = false)

        r.isBlocked shouldBe false
        val sqls = r.sqls()
        sqls.count("CREATE UNIQUE INDEX \"ix_u\"") shouldBe 1
        sqls.count("DROP INDEX") shouldBe 0
        MigrationStreamClassifier.unsupportedTransactionScopeReason(r.statements).shouldBeNull()
    }

    test("neuer Index neben einem Nullability-Neubau: UP legt ihn genau einmal an, DOWN raeumt nichts nach") {
        val current = schema("t" to table())
        val desired = schema("t" to table(aRequired = true, indices = listOf(ix("ix_b", "b"))))

        val up = render(current, desired, up = true).sqls()
        val down = render(current, desired, up = false).sqls()

        up.count("CREATE INDEX \"ix_b\"") shouldBe 1
        up.count("DROP INDEX") shouldBe 0
        down.count("CREATE INDEX") shouldBe 0
        down.count("DROP INDEX") shouldBe 0
    }

    test("entfallender Index neben einem Nullability-Neubau: UP ohne DROP INDEX, DOWN legt ihn genau einmal an") {
        val current = schema("t" to table(indices = listOf(ix("ix_b", "b"))))
        val desired = schema("t" to table(aRequired = true))

        val up = render(current, desired, up = true).sqls()
        val down = render(current, desired, up = false).sqls()

        up.count("DROP INDEX") shouldBe 0
        up.count("CREATE INDEX") shouldBe 0
        down.count("CREATE INDEX \"ix_b\"") shouldBe 1
        down.count("DROP INDEX") shouldBe 0
    }

    test("unveraenderter Index im Neubau: genau ein CREATE INDEX") {
        val keep = listOf(ix("ix_b", "b"))
        val current = schema("t" to table(indices = keep))
        val desired = schema("t" to table(aRequired = true, indices = keep))

        render(current, desired, up = true).sqls().count("CREATE INDEX \"ix_b\"") shouldBe 1
    }

    test("Neubau plus fremde Anweisung (AddColumn einer anderen Tabelle) bleibt ein gemischter Strom") {
        val other = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val current = schema("t" to table(), "x" to other)
        val desired = schema(
            "t" to table(aRequired = true),
            "x" to other.copy(columns = other.columns + ("e" to ColumnDefinition(NeutralType.Text()))),
        )

        val r = render(current, desired, up = true)

        MigrationStreamClassifier.unsupportedTransactionScopeReason(r.statements).shouldNotBeNull()
    }

    test("nicht darstellbarer Index (Rohtext) neben einem Neubau blockiert den Eimer") {
        val current = schema("t" to table())
        val desired = schema("t" to table(aRequired = true, indices = listOf(ix("ix_w", "b", where = "(b)::text > 'x'"))))

        val r = render(current, desired, up = true)

        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.any { it.code == "INDEX_RAW_TEXT_NOT_PORTABLE" } shouldBe true
        r.sqls().count("CREATE TABLE").shouldBe(0)
    }

    test("Hinweis auf verlorene INCLUDE-Spalten erscheint fuer einen absorbierten Index genau einmal (UP und DOWN)") {
        val covering = ix("ix_c", "b").copy(includeColumns = listOf("a"))
        val without = schema("t" to table())
        val with = schema("t" to table(aRequired = true, indices = listOf(covering)))

        render(without, with, up = true).diagnostics.count { it.code == "W142" } shouldBe 1
        val back = schema("t" to table(indices = listOf(covering)))
        render(back, schema("t" to table(aRequired = true)), up = false).diagnostics.count { it.code == "W142" } shouldBe 1
    }

    // ── FULLTEXT derselben Tabelle: der Neubau baut FTS5 genau einmal je Ziel-Index ──

    val ft = ix("ft_b", "b", type = IndexType.FULLTEXT)

    fun List<String>.createsOf(fts: String) = count("CREATE VIRTUAL TABLE \"$fts\"")

    fun List<String>.triggersOf(fts: String) =
        count { it.startsWith("CREATE TRIGGER \"${fts}_a") }

    fun MigrationDdlResult.assertSingleStream() {
        isBlocked shouldBe false
        statements.all { it.transactionScope == TransactionScope.STREAM_OWNED } shouldBe true
        MigrationStreamClassifier.unsupportedTransactionScopeReason(statements).shouldBeNull()
    }

    test("neuer FULLTEXT-Index neben einem Neubau: ein Strom, UP baut FTS5 genau einmal, DOWN raeumt es ab") {
        val current = schema("t" to table(uUnique = false, indices = listOf(ix("ix_u", "u", unique = true))))
        val desired = schema("t" to table(uUnique = true, indices = listOf(ft)))

        val up = render(current, desired, up = true)
        val down = render(current, desired, up = false)

        up.assertSingleStream()
        down.assertSingleStream()
        up.sqls().createsOf("ft_b") shouldBe 1
        up.sqls().triggersOf("ft_b") shouldBe 3
        up.sqls().count("INSERT INTO \"ft_b\"(\"ft_b\") VALUES('rebuild')") shouldBe 1
        down.sqls().createsOf("ft_b") shouldBe 0
        down.sqls().count("DROP TABLE IF EXISTS \"ft_b\"") shouldBe 1
        down.sqls().count("CREATE UNIQUE INDEX \"ix_u\"") shouldBe 1
    }

    test("neuer FULLTEXT-Index neben einem Nullability-Neubau: genau ein FTS5-Aufbau, ohne Zusatz-Operation") {
        val current = schema("t" to table())
        val desired = schema("t" to table(aRequired = true, indices = listOf(ft)))

        val up = render(current, desired, up = true)

        up.assertSingleStream()
        up.sqls().createsOf("ft_b") shouldBe 1
        up.sqls().count("CREATE VIRTUAL TABLE") shouldBe 1
    }

    test("entfallender FULLTEXT-Index neben einem Neubau: UP raeumt FTS5 ab, DOWN legt es genau einmal an") {
        val current = schema("t" to table(indices = listOf(ft)))
        val desired = schema("t" to table(aRequired = true))

        val up = render(current, desired, up = true)
        val down = render(current, desired, up = false)

        up.assertSingleStream()
        down.assertSingleStream()
        up.sqls().createsOf("ft_b") shouldBe 0
        up.sqls().count("DROP TABLE IF EXISTS \"ft_b\"") shouldBe 1
        down.sqls().createsOf("ft_b") shouldBe 1
        down.sqls().count("DROP TABLE IF EXISTS \"ft_b\"") shouldBe 0
    }

    test("geaenderter FULLTEXT-Index neben einem Neubau: je Richtung genau ein Aufbau und genau ein Abraeumen") {
        val before = ix("ft_b", "b", type = IndexType.FULLTEXT)
        val after = ix("ft_b", "a", type = IndexType.FULLTEXT)
        val current = schema("t" to table(indices = listOf(before)))
        val desired = schema("t" to table(aRequired = true, indices = listOf(after)))

        for (up in listOf(true, false)) {
            val r = render(current, desired, up = up)
            r.assertSingleStream()
            r.sqls().createsOf("ft_b") shouldBe 1
            r.sqls().count("DROP TABLE IF EXISTS \"ft_b\"") shouldBe 1
        }
    }

    test("FULLTEXT-Index neben einem Neubau, Plain-Index bleibt im Neubau") {
        val keep = ix("ix_b", "b")
        val current = schema("t" to table(indices = listOf(keep)))
        val desired = schema("t" to table(aRequired = true, indices = listOf(keep, ft)))

        val up = render(current, desired, up = true)

        up.assertSingleStream()
        up.sqls().count("CREATE INDEX \"ix_b\"") shouldBe 1
        up.sqls().createsOf("ft_b") shouldBe 1
    }

    for ((label, columnName) in listOf("WITHOUT ROWID" to "b", "reservierter Spaltenname" to "rank")) {
        test("FULLTEXT neben einem Neubau, FTS5 nicht baubar ($label): W132 genau einmal, kein FTS5, kein Blocker") {
            val meta = if (label == "WITHOUT ROWID") TableMetadata(withoutRowid = true) else null
            val extra = if (columnName == "rank") mapOf("rank" to ColumnDefinition(NeutralType.Text())) else emptyMap()
            val index = ix("ft_b", columnName, type = IndexType.FULLTEXT)
            val current = schema("t" to table(extra = extra).copy(metadata = meta))
            val desired = schema("t" to table(aRequired = true, indices = listOf(index), extra = extra).copy(metadata = meta))

            val up = render(current, desired, up = true)

            up.assertSingleStream()
            up.diagnostics.count { it.code == "W132" } shouldBe 1
            up.sqls().count("CREATE VIRTUAL TABLE") shouldBe 0
        }
    }

    test("FULLTEXT-Index einer anderen Tabelle neben einem Neubau bleibt ein gemischter Strom") {
        val other = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "b" to ColumnDefinition(NeutralType.Text()),
            ),
            primaryKey = listOf("id"),
        )
        val current = schema("t" to table(), "x" to other)
        val desired = schema("t" to table(aRequired = true), "x" to other.copy(indices = listOf(ft)))

        val r = render(current, desired, up = true)

        MigrationStreamClassifier.unsupportedTransactionScopeReason(r.statements).shouldNotBeNull()
    }

    test("ohne Neubau bleibt AddIndex eine einfache Anweisung") {
        val current = schema("t" to table())
        val desired = schema("t" to table(indices = listOf(ix("ix_b", "b"))))

        val r = render(current, desired, up = true)

        r.sqls().filter { it.startsWith("CREATE INDEX") }.size shouldBe 1
        r.statements.none { it.transactionScope == TransactionScope.STREAM_OWNED } shouldBe true
        r.statements.map { it.sql }.filter { it.startsWith("CREATE TABLE") }.shouldBeEmpty()
    }
})
