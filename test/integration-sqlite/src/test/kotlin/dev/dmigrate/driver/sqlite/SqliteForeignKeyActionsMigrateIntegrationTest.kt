package dev.dmigrate.driver.sqlite

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferentialAction
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
import kotlin.io.path.createTempDirectory

/**
 * Was `PRAGMA foreign_key_list` nach einem `schema migrate --execute` sagt.
 *
 * Ein Unit-Test zeigt die Zeichenkette, die der Renderer schreibt; ob SQLite
 * die Aktionen danach **fuehrt**, sagt nur der Server. Beide Faelle stehen
 * hier: die neue Tabelle und der Rebuild, der eine bestehende neu schreibt.
 * Vor dem Fix meldete `foreign_key_list` in beiden `NO ACTION` (gemessen an
 * 3.45), und der Post-Compare sah Drift.
 */
class SqliteForeignKeyActionsMigrateIntegrationTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.register(SqliteDriver()) }

    fun newPool(): ConnectionPool = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null, port = null, database = ":memory:", user = null, password = null,
        ),
    )

    val parent = TableDefinition(
        columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
        primaryKey = listOf("id"),
    )

    fun schemaWith(noteRequired: Boolean) = SchemaDefinition(
        name = "sqlite_fk_actions", version = "1",
        tables = linkedMapOf(
            "fk_parent" to parent,
            "fk_child" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "parent_id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "note" to ColumnDefinition(NeutralType.Text(), required = noteRequired),
                ),
                primaryKey = listOf("id"),
                constraints = listOf(
                    ConstraintDefinition(
                        name = "fk_child_parent",
                        type = ConstraintType.FOREIGN_KEY,
                        columns = listOf("parent_id"),
                        references = ConstraintReferenceDefinition(
                            table = "fk_parent",
                            columns = listOf("id"),
                            onDelete = ReferentialAction.CASCADE,
                            onUpdate = ReferentialAction.RESTRICT,
                        ),
                    ),
                ),
            ),
        ),
    )

    fun migrate(pool: ConnectionPool, want: SchemaDefinition): Pair<Int, String> {
        val tmp = createTempDirectory("sqlite-fk-actions")
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

    /** Die Aktionen, die der Server fuehrt: `on_update` und `on_delete`. */
    fun actions(pool: ConnectionPool): Pair<String, String> = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA foreign_key_list(fk_child)").use { rs ->
                if (!rs.next()) return@use "kein Fremdschluessel" to "kein Fremdschluessel"
                rs.getString("on_update") to rs.getString("on_delete")
            }
        }
    }

    test("CreateTable: the server keeps ON DELETE and ON UPDATE, and the post-compare is clean") {
        val pool = newPool()
        try {
            val (exit, report) = migrate(pool, schemaWith(noteRequired = false))

            withClue(report) { exit shouldBe 0 }
            actions(pool) shouldBe ("RESTRICT" to "CASCADE")
        } finally {
            pool.close()
        }
    }

    test("a rebuild does not take them away") {
        val pool = newPool()
        try {
            migrate(pool, schemaWith(noteRequired = false)).first shouldBe 0
            actions(pool) shouldBe ("RESTRICT" to "CASCADE")

            // Die Nullbarkeits-Aenderung schreibt die Tabelle neu.
            val (exit, report) = migrate(pool, schemaWith(noteRequired = true))

            withClue(report) { exit shouldBe 0 }
            actions(pool) shouldBe ("RESTRICT" to "CASCADE")
        } finally {
            pool.close()
        }
    }
})
