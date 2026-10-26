package dev.dmigrate.driver.sqlite

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.SchemaRollbackRequest
import dev.dmigrate.cli.commands.SchemaRollbackRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
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
import java.sql.SQLException
import kotlin.io.path.createTempDirectory

/**
 * Gegenprobe zu Issue #61: auf SQLite nimmt der Rollback eine erweiterte Werteliste zurueck.
 *
 * Der Tabellenneubau schreibt den benannten CHECK in beiden Richtungen neu; dort gibt es keinen
 * `ALTER … DROP/ADD CONSTRAINT` mit unbenanntem CHECK, an dem der Rueckweg haengen koennte. Die Spec
 * haelt fest, dass dieses Verhalten das Soll fuer PostgreSQL ist.
 */
class SqliteEnumRollbackIntegrationTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.register(SqliteDriver()) }

    fun newPool(): ConnectionPool = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null, port = null, database = ":memory:", user = null, password = null,
        ),
    )

    fun schemaWith(vararg values: String) = SchemaDefinition(
        name = "sqlite_enum_rollback", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer),
                    "e" to ColumnDefinition(NeutralType.Enum(values = values.toList()), required = true),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun operand(pool: ConnectionPool) = ResolvedSchemaOperand(
        reference = "live-sqlite",
        schema = SqliteSchemaReader().read(pool).schema,
        validation = ValidationResult(),
        dialect = DatabaseDialect.SQLITE,
    )

    fun accepts(pool: ConnectionPool, id: Int, value: String): Boolean = try {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("INSERT INTO t (id, e) VALUES ($id, '$value')") }
        }
        true
    } catch (e: SQLException) {
        if (e.message?.contains("CHECK constraint failed") != true) throw e
        false
    }

    test("a widened list is rolled back: the database rejects the new value again") {
        val pool = newPool()
        val tmp = createTempDirectory("sqlite-enum-rollback")
        try {
            // So legt `schema generate` die Tabelle an: benannter CHECK.
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use {
                    it.execute(
                        "CREATE TABLE t (id INTEGER PRIMARY KEY, e TEXT NOT NULL, " +
                            "CONSTRAINT ck_t_e CHECK (\"e\" IN ('a', 'b')))",
                    )
                }
            }
            val down = tmp.resolve("down.sql")
            val errors = mutableListOf<String>()
            val up = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", schemaWith("a", "b", "c"), ValidationResult()) },
                dbLoader = { _, _ -> operand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.SQLITE) SqliteDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}", target = "db:placeholder",
                    dialect = DatabaseDialect.SQLITE, report = tmp.resolve("report.json"),
                    rollbackOutput = down, generateRollback = true, execute = true,
                ),
            )
            withClue(errors.joinToString("; ")) { up shouldBe 0 }
            accepts(pool, 1, "c") shouldBe true
            pool.borrow().asJdbc().use { conn -> conn.createStatement().use { it.execute("DELETE FROM t") } }

            val rollbackExit = SchemaRollbackRunner(
                dbLoader = { _, _ -> operand(pool) },
                executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(SchemaRollbackRequest(source = down, target = "db:placeholder", execute = true, allowDestructive = true))

            withClue(errors.joinToString("; ")) { rollbackExit shouldBe 0 }
            accepts(pool, 2, "c") shouldBe false
            accepts(pool, 3, "a") shouldBe true
        } finally {
            tmp.toFile().deleteRecursively()
            pool.close()
        }
    }
})
