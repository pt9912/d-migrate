package dev.dmigrate.driver.sqlite

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.SchemaDefinition
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
 * Was auf SQLite geschieht, wenn ein Soll die **Identity** einer Spalte ueber
 * `generation` aendert.
 *
 * **Dort steckt die Identity nicht.** Der Reverse liefert sie als
 * Spalten**typ** (`Identifier(autoIncrement = true)`, gerendert als
 * `INTEGER PRIMARY KEY AUTOINCREMENT`), `generation` bleibt leer — die erste
 * Zusicherung haelt genau das fest. Ein Soll, das sie ueber `generation`
 * setzt, beschreibt deshalb etwas, das der Tabellen-Neubau nicht herstellen
 * kann.
 *
 * **Gemessen, was vorher passierte:** der Lauf baute die Tabelle wirklich um
 * (`CREATE TABLE …__dmg_rebuild_…`, `INSERT … SELECT`, `DROP TABLE`,
 * `RENAME`), die neue Tabelle trug kein `AUTOINCREMENT`, und der
 * Post-Compare meldete Drift (Exit 5). Ein Neubau fuer nichts -- teurer und
 * riskanter als eine benannte Ablehnung.
 */
class SqliteIdentityTransitionIntegrationTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.register(SqliteDriver()) }

    fun newPool(ddl: String): ConnectionPool {
        val pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.SQLITE,
                host = null, port = null, database = ":memory:", user = null, password = null,
            ),
        )
        pool.borrow().asJdbc().use { conn -> conn.createStatement().use { it.execute(ddl) } }
        return pool
    }

    fun liveSchema(pool: ConnectionPool): SchemaDefinition = SqliteSchemaReader().read(pool).schema

    fun tableSql(pool: ConnectionPool): String = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='counters'")
                .use { rs -> if (rs.next()) rs.getString(1) else "<keine Tabelle>" }
        }
    }

    fun migrate(pool: ConnectionPool, want: SchemaDefinition): Pair<Int, List<String>> {
        val tmp = createTempDirectory("sqlite-identity-probe")
        return try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ ->
                    ResolvedSchemaOperand(
                        reference = "live-sqlite",
                        schema = liveSchema(pool),
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.SQLITE,
                    )
                },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, _, serverForm ->
                    SchemaComparator(projection, null, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.SQLITE) SqliteDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.SQLITE,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            exit to (executed + errors)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("the reverse carries SQLite identity in the column type, not in generation") {
        newPool("CREATE TABLE counters (id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT)").use { pool ->
            val idCol = liveSchema(pool).tables.getValue("counters").columns.getValue("id")
            idCol.type.toString() shouldContain "autoIncrement=true"
            idCol.generation shouldBe null
        }
    }

    test("a desired schema that adds identity via generation is refused, and the table is left alone") {
        newPool("CREATE TABLE counters (id INTEGER PRIMARY KEY, label TEXT)").use { pool ->
            val live = liveSchema(pool)
            val idCol = live.tables.getValue("counters").columns.getValue("id")
            val want = live.copy(
                tables = live.tables.mapValues { (_, t) ->
                    t.copy(
                        columns = LinkedHashMap(t.columns).also {
                            it["id"] = idCol.copy(generation = ColumnGeneration.Identity(IdentityMode.BY_DEFAULT))
                        },
                    )
                },
            )

            val (exit, lines) = migrate(pool, want)

            // MANUAL_ACTION_REQUIRED, nicht Drift: der Lauf sagt es vorher.
            exit shouldBe 0
            withClue(lines.joinToString(" | ")) {
                lines.any { it.contains("__dmg_rebuild_") } shouldBe true
            }
            tableSql(pool) shouldBe "CREATE TABLE counters (id INTEGER PRIMARY KEY, label TEXT)"
        }
    }

    /**
     * Die Gegenrichtung braucht keine Ablehnung: weil der Reverse die Identity
     * nie in `generation` fuehrt, entsteht ueberhaupt kein Unterschied -- der
     * Lauf plant nichts und die Tabelle bleibt, wie sie ist.
     */
    test("dropping identity via generation is not even a difference") {
        newPool("CREATE TABLE counters (id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT)").use { pool ->
            val live = liveSchema(pool)
            val idCol = live.tables.getValue("counters").columns.getValue("id")
            val want = live.copy(
                tables = live.tables.mapValues { (_, t) ->
                    t.copy(columns = LinkedHashMap(t.columns).also { it["id"] = idCol.copy(generation = null) })
                },
            )

            val (exit, lines) = migrate(pool, want)

            exit shouldBe 0
            withClue(lines.joinToString(" | ")) { lines.none { it.contains("__dmg_rebuild_") } shouldBe true }
            tableSql(pool) shouldBe "CREATE TABLE counters (id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT)"
        }
    }
})
