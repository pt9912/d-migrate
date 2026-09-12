package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.PostgresServerVersion
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Der Berechnungsausdruck einer Spalte, in place gesetzt — und die Faelle, in
 * denen der Renderer das ablehnt.
 *
 * Die Ablehnung ist hier so wichtig wie die Anweisung: unterhalb von
 * PostgreSQL 17 gibt es `SET EXPRESSION` nicht, und der einzige Ausweg naehme
 * gemessen den Index stillschweigend mit und scheiterte an einer abhaengigen
 * Sicht. Etwas nicht zu tun ist dort besser, als es still falsch zu tun.
 */
class PostgresDiffGenerationOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun optionsFor(version: PostgresServerVersion?) = DdlGenerationOptions(
        dialectContext = DdlDialectContext.Postgres(serverVersion = version),
    )

    fun diffChanging(from: String, to: String) = SchemaDiff(
        tablesChanged = listOf(
            TableDiff(
                name = "order_line",
                columnsChanged = listOf(
                    ColumnDiff(
                        name = "line_total",
                        generation = ValueChange(
                            before = ColumnGeneration.Computed(from, stored = true),
                            after = ColumnGeneration.Computed(to, stored = true),
                        ),
                    ),
                ),
            ),
        ),
    )

    fun render(version: PostgresServerVersion?, diff: SchemaDiff = diffChanging("q * p", "q * p * 2")) =
        gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), optionsFor(version))

    test("from 17 on the expression is set in place") {
        val result = render(PostgresServerVersion(18, 6))

        result.statements.single().sql shouldContain
            """ALTER TABLE "order_line" ALTER COLUMN "line_total" SET EXPRESSION AS (q * p * 2);"""
        result.blockers.shouldBeEmpty()
    }

    test("below 17 the run blocks instead of dropping and recreating the column") {
        val result = render(PostgresServerVersion(16, 9))

        result.statements.shouldBeEmpty()
        result.blockers.single().reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        val message = result.diagnostics.single().message
        message shouldContain "from PostgreSQL 17 on"
        message shouldContain "16.9"
    }

    test("an unknown version does not get the capability granted") {
        val result = render(version = null)

        result.statements.shouldBeEmpty()
        result.diagnostics.single().message shouldContain "unknown (file-to-file run)"
    }

    test("turning a computed column into an ordinary one is refused by name") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "order_line",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "line_total",
                            generation = ValueChange(
                                before = ColumnGeneration.Computed("q * p", stored = true),
                                after = null,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = render(PostgresServerVersion(18, 6), diff)

        result.statements.shouldBeEmpty()
        result.diagnostics.single().message shouldContain "cannot turn a generated column into an ordinary one"
    }
})
