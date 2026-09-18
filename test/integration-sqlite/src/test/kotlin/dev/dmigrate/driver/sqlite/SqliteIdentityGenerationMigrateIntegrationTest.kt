package dev.dmigrate.driver.sqlite

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory

/**
 * S2 — was in der Datenbank steht, nachdem `schema migrate --execute` eine
 * Spalte mit `generation: identity` angelegt hat.
 *
 * Ein Unit-Test zeigt die Zeichenkette, die der Renderer schreibt; ob SQLite
 * danach wirklich einen AUTOINCREMENT-Schluessel fuehrt, sagt nur der Server
 * — `sqlite_sequence` entsteht nur fuer eine solche Tabelle. Vor dem Fix
 * legte der Migrate-Pfad die Spalte als blankes `INTEGER` mit eigener
 * `PRIMARY KEY`-Klausel an (**Ursache 1** von
 * `docs/planning/open/sqlite-migrate-biginteger-identity-render-gap.md`).
 *
 * **Ursache 2 bleibt, und dieser Test misst sie.** Der Post-Compare liest das
 * Ziel ohne Reverse-Praefenz zurueck und bekommt `identifier(auto)`; das Soll
 * sagt `biginteger` + `generation: identity`. Beide Schreibweisen ergeben
 * dieselbe Spalte — der Abdruck faltet sie aber nur in `generation`
 * (`MigrationFingerprint.impliedGeneration`), nicht im **Typ**. Der Lauf endet
 * deshalb mit Exit 5 und `POST_EXECUTE_DRIFT`, obwohl die Datenbank genau so
 * steht, wie das Soll sie wollte. Der Gegenversuch mit derselben Spalte als
 * `identifier` zeigt es: **dieselbe DDL**, Exit 0.
 */
class SqliteIdentityGenerationMigrateIntegrationTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.register(SqliteDriver()) }

    fun newPool(): ConnectionPool = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null, port = null, database = ":memory:", user = null, password = null,
        ),
    )

    fun schemaWith(labelRequired: Boolean, asIdentifier: Boolean = false) = SchemaDefinition(
        name = "sqlite_identity", version = "1",
        tables = linkedMapOf(
            "id_table" to TableDefinition(
                columns = linkedMapOf(
                    "id" to if (asIdentifier) {
                        ColumnDefinition(NeutralType.Identifier(autoIncrement = true), ordinal = 1)
                    } else {
                        ColumnDefinition(
                            NeutralType.BigInteger,
                            generation = ColumnGeneration.Identity(),
                            ordinal = 1,
                        )
                    },
                    "label" to ColumnDefinition(NeutralType.Text(40), required = labelRequired, ordinal = 2),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun migrate(pool: ConnectionPool, want: SchemaDefinition): Pair<Int, String> {
        val tmp = createTempDirectory("sqlite-identity")
        return try {
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ ->
                    ResolvedSchemaOperand(
                        reference = "live-sqlite",
                        schema = SqliteSchemaReader().read(pool).schema,
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.SQLITE,
                    )
                },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.SQLITE) SqliteDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ ->
                    executeAgainstPool(pool, segments.flatMap { it.statements })
                },
                renderReport = { r, _ -> r.toString() },
                printError = { _, _ -> },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.SQLITE,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            val report = runCatching { java.nio.file.Files.readString(tmp.resolve("report.json")) }.getOrElse { "" }
            exit to report
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** Der gespeicherte `CREATE TABLE`-Text der Tabelle. */
    fun storedDdl(pool: ConnectionPool): String = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT sql FROM sqlite_master WHERE name = 'id_table'").use { rs ->
                if (rs.next()) rs.getString(1) else "keine Tabelle"
            }
        }
    }

    /**
     * `sqlite_sequence` entsteht **nur** fuer eine Tabelle mit AUTOINCREMENT.
     * Der Text allein koennte auch ein Kommentar sein; diese Tabelle ist der
     * Beleg des Servers.
     */
    fun hasSequenceRow(pool: ConnectionPool): Boolean = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'",
            ).use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
    }

    test("CreateTable: der Server fuehrt einen AUTOINCREMENT-Schluessel") {
        val pool = newPool()
        try {
            val (_, report) = migrate(pool, schemaWith(labelRequired = false))

            withClue(report) {
                storedDdl(pool) shouldContain "AUTOINCREMENT"
                hasSequenceRow(pool) shouldBe true
            }
        } finally {
            pool.close()
        }
    }

    test("ein Neubau nimmt es nicht wieder weg") {
        val pool = newPool()
        try {
            migrate(pool, schemaWith(labelRequired = false))
            storedDdl(pool) shouldContain "AUTOINCREMENT"

            // Die Nullbarkeits-Aenderung schreibt die Tabelle neu.
            val (_, report) = migrate(pool, schemaWith(labelRequired = true))

            withClue(report) {
                storedDdl(pool) shouldContain "AUTOINCREMENT"
                hasSequenceRow(pool) shouldBe true
            }
        } finally {
            pool.close()
        }
    }

    /**
     * Ursache 2, gemessen und gepinnt: **dieselbe** DDL, zwei Ausgaenge. Die
     * Spalte als `identifier` konvergiert; als `biginteger` +
     * `generation: identity` meldet der Post-Compare Drift, weil der Abdruck
     * die beiden Schreibweisen im Typ nicht zusammenfaltet und der Re-Read
     * ohne Praeferenz `identifier` liefert.
     *
     * Verschwindet dieser Unterschied, ist Ursache 2 geschlossen — und dieser
     * Test rot. Das ist beabsichtigt: er ist der Waechter ueber dem offenen
     * Punkt, nicht ueber dem Fix.
     */
    test("Ursache 2: dieselbe DDL, aber die Schreibweise des Solls entscheidet den Ausgang") {
        val asIdentifier = newPool()
        val asIdentity = newPool()
        try {
            val (identifierExit, identifierReport) = migrate(asIdentifier, schemaWith(false, asIdentifier = true))
            val identifierDdl = storedDdl(asIdentifier)

            val (identityExit, identityReport) = migrate(asIdentity, schemaWith(false))
            val identityDdl = storedDdl(asIdentity)

            withClue("die erzeugte DDL muss dieselbe sein — sonst misst der Vergleich etwas anderes") {
                identityDdl shouldBe identifierDdl
            }
            withClue(identifierReport) { identifierExit shouldBe 0 }
            withClue(identityReport) {
                identityExit shouldBe DRIFT_EXIT
                identityReport shouldContain "POST_EXECUTE_DRIFT"
            }
        } finally {
            asIdentifier.close()
            asIdentity.close()
        }
    }
}) {
    private companion object {
        /** `schema migrate`: der Post-Compare meldet Drift (spec/cli-spec.md). */
        const val DRIFT_EXIT = 5
    }
}
