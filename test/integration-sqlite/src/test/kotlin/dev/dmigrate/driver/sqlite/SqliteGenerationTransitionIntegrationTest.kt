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
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.createTempDirectory

/**
 * Die Generationsuebergaenge auf SQLite — der Dialekt ohne `ALTER COLUMN`, der
 * jede Spaltenaenderung ueber den Tabellen-Neubau faehrt.
 *
 * **Zwei Faelle, zwei Antworten.** Den Wechsel berechnet ↔ gewoehnlich kann der
 * Neubau (und tut es, unten belegt); die **Identity** kann er nicht — sie
 * steckt hier im Spaltentyp.
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
class SqliteGenerationTransitionIntegrationTest : FunSpec({

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
            exit shouldBe 8
            withClue(lines.joinToString(" | ")) {
                lines.none { it.contains("__dmg_rebuild_") } shouldBe true
            }
            tableSql(pool) shouldBe "CREATE TABLE counters (id INTEGER PRIMARY KEY, label TEXT)"
        }
    }

    /**
     * **Die Speicherform** kann der Neubau ebenfalls wechseln — das Handbuch
     * behauptete zuvor, das gehe „auf keinem Dialekt". In place stimmt das; auf
     * SQLite ist der Neubau aber ohnehin der Weg, und er traegt die neue Form.
     */
    test("the storage form changes through the rebuild") {
        newPool(
            "CREATE TABLE counters (id INTEGER PRIMARY KEY, qty INTEGER NOT NULL, " +
                "total INTEGER GENERATED ALWAYS AS (qty * 2) STORED)",
        ).use { pool ->
            val live = liveSchema(pool)
            val col = live.tables.getValue("counters").columns.getValue("total")
            val want = live.copy(
                tables = live.tables.mapValues { (_, t) ->
                    t.copy(
                        columns = LinkedHashMap(t.columns).also {
                            it["total"] = col.copy(
                                generation = ColumnGeneration.Computed("qty * 2", stored = false),
                            )
                        },
                    )
                },
            )

            val (exit, lines) = migrate(pool, want)

            withClue(lines.joinToString(" | ")) { exit shouldBe 0 }
            tableSql(pool) shouldContain "GENERATED ALWAYS AS (qty * 2) VIRTUAL"
        }
    }

    /**
     * Der Neubau kann den Kind-Wechsel: die Spalte verliert ihre Berechnung
     * und **behaelt den gerechneten Wert** als gewoehnliche Daten — er steht in
     * der `INSERT … SELECT`-Spaltenliste des Neubaus.
     */
    test("a computed column becomes an ordinary one, and keeps the value it had") {
        newPool(
            "CREATE TABLE counters (id INTEGER PRIMARY KEY, qty INTEGER NOT NULL, " +
                "total INTEGER GENERATED ALWAYS AS (qty * 2) STORED)",
        ).use { pool ->
            pool.borrow().asJdbc().use { c ->
                c.createStatement().use { it.execute("INSERT INTO counters (id, qty) VALUES (1, 21)") }
            }
            val live = liveSchema(pool)
            val col = live.tables.getValue("counters").columns.getValue("total")
            val want = live.copy(
                tables = live.tables.mapValues { (_, t) ->
                    t.copy(columns = LinkedHashMap(t.columns).also { it["total"] = col.copy(generation = null) })
                },
            )

            val (exit, lines) = migrate(pool, want)

            withClue(lines.joinToString(" | ")) { exit shouldBe 0 }
            tableSql(pool) shouldNotContain "GENERATED ALWAYS"
            pool.borrow().asJdbc().use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT total FROM counters WHERE id = 1").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 42
                    }
                }
            }
        }
    }

    /**
     * Und die Gegenrichtung: die Spalte bleibt aus der `INSERT`-Liste des
     * Neubaus heraus, SQLite rechnet sie neu — der alte Wert (7) wird durch den
     * gerechneten (42) ersetzt, was das Soll genau so verlangt.
     */
    test("an ordinary column becomes computed, and the server recomputes it") {
        newPool("CREATE TABLE counters (id INTEGER PRIMARY KEY, qty INTEGER NOT NULL, total INTEGER)").use { pool ->
            pool.borrow().asJdbc().use { c ->
                c.createStatement().use { it.execute("INSERT INTO counters (id, qty, total) VALUES (1, 21, 7)") }
            }
            val live = liveSchema(pool)
            val col = live.tables.getValue("counters").columns.getValue("total")
            val want = live.copy(
                tables = live.tables.mapValues { (_, t) ->
                    t.copy(
                        columns = LinkedHashMap(t.columns).also {
                            it["total"] = col.copy(generation = ColumnGeneration.Computed("qty * 2", stored = true))
                        },
                    )
                },
            )

            val (exit, lines) = migrate(pool, want)

            withClue(lines.joinToString(" | ")) { exit shouldBe 0 }
            tableSql(pool) shouldContain "GENERATED ALWAYS AS (qty * 2) STORED"
            pool.borrow().asJdbc().use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT total FROM counters WHERE id = 1").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 42
                    }
                }
            }
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
