package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
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

    /**
     * Gemessen gegen 18.6: `DROP EXPRESSION` laesst den gespeicherten Wert als
     * gewoehnliche Daten stehen (42 blieb 42) und macht die Spalte
     * beschreibbar — genau das, was das Soll sagt.
     */
    test("turning a computed column into an ordinary one renders DROP EXPRESSION") {
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

        result.statements.single().sql shouldContain "ALTER COLUMN \"line_total\" DROP EXPRESSION"
        result.diagnostics.shouldBeEmpty()
    }

    /**
     * Gemessen gegen 18.6: `SET EXPRESSION` auf einer gewoehnlichen Spalte
     * antwortet „column … is not a generated column". Vorher rendert dieser
     * Pfad genau das — ein Statement, das der Server ablehnt.
     */
    test("making an ordinary column computed is refused instead of rendering DDL the server rejects") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "order_line",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "line_total",
                            generation = ValueChange(
                                before = null,
                                after = ColumnGeneration.Computed("q * p", stored = true),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = render(PostgresServerVersion(18, 6), diff)

        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "would make the ordinary column"
        message shouldContain "is not a generated column"
    }

    /**
     * PostgreSQL **nimmt** `ADD GENERATED … AS IDENTITY` auf einer gefuellten
     * Spalte an, aber die neue Sequenz beginnt bei 1 — ohne Nachziehen
     * kollidiert der naechste `INSERT` mit dem Bestand (live gemessen gegen
     * 18.6, `column-generation-diff-unmapped.md`). Drei Anweisungen fuer
     * eine Operation: `SET NOT NULL` (No-op, falls schon gesetzt),
     * `ADD GENERATED … AS IDENTITY`, dann die Sequenz-Nachziehung per
     * `setval`.
     */
    test("adding an identity renders SET NOT NULL, ADD GENERATED, and a setval reseed") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "counters",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "id",
                            generation = ValueChange(
                                before = null,
                                after = ColumnGeneration.Identity(IdentityMode.BY_DEFAULT),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = render(PostgresServerVersion(18, 6), diff)

        result.statements.map { it.sql } shouldBe listOf(
            """ALTER TABLE "counters" ALTER COLUMN "id" SET NOT NULL;""",
            """ALTER TABLE "counters" ALTER COLUMN "id" ADD GENERATED BY DEFAULT AS IDENTITY;""",
            """SELECT setval(pg_get_serial_sequence('"counters"', 'id'), """ +
                """GREATEST(COALESCE(m, 1), 1), m IS NOT NULL AND m >= 1) """ +
                """FROM (SELECT max("id") AS m FROM "counters") s;""",
        )
        result.blockers.shouldBeEmpty()
        result.diagnostics.shouldBeEmpty()
    }

    test("dropping an identity renders DROP IDENTITY") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "order_line",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "line_total",
                            generation = ValueChange(
                                before = ColumnGeneration.Identity(IdentityMode.ALWAYS),
                                after = null,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = render(PostgresServerVersion(18, 6), diff)

        result.statements.single().sql shouldContain "ALTER COLUMN \"line_total\" DROP IDENTITY"
    }

    test("switching the identity mode renders SET GENERATED") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "order_line",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "line_total",
                            generation = ValueChange(
                                before = ColumnGeneration.Identity(IdentityMode.ALWAYS),
                                after = ColumnGeneration.Identity(IdentityMode.BY_DEFAULT),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = render(PostgresServerVersion(18, 6), diff)

        result.statements.single().sql shouldContain "ALTER COLUMN \"line_total\" SET GENERATED BY DEFAULT"
    }
})
