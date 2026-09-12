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
import io.kotest.matchers.string.shouldNotContain

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
     * Der Wortlaut hier war falsch und ist nachgemessen worden: PostgreSQL
     * **kann** eine berechnete Spalte zurueckverwandeln (`DROP EXPRESSION`,
     * gegen 18.6 belegt). Geblockt wird trotzdem — der Uebergang ist ungebaut —
     * aber mit dem Befehl in der Meldung, damit der Anwender ihn selbst fahren
     * kann.
     */
    test("turning a computed column into an ordinary one is refused, naming the command that does it") {
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
        val message = result.diagnostics.single().message
        message shouldContain "would drop the computed expression"
        message shouldContain "DROP EXPRESSION"
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
     * Eine Identity-Aenderung fiel in denselben Zweig und bekam eine Meldung
     * ueber den *Berechnungsausdruck* — beides falsch: es gibt keinen, und
     * PostgreSQL kann zwei dieser Uebergaenge sehr wohl in place.
     */
    test("an identity change is not reported as a computed-expression matter") {
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

        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "changes the identity of"
        message shouldContain "DROP IDENTITY"
        message shouldNotContain "would drop the computed expression"
    }
})
