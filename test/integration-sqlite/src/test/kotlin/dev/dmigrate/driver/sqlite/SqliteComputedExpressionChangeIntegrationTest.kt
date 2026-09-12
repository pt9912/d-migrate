package dev.dmigrate.driver.sqlite

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.RawTextAuthorship
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
 * Ein geaenderter Berechnungsausdruck wird auch auf SQLite angewandt — ueber
 * den Tabellen-Neubau, den dieser Dialekt fuer jedes `ALTER COLUMN` geht.
 *
 * Was daran nicht selbstverstaendlich ist: der Neubau kopiert die Daten mit
 * `INSERT INTO neu (spalten) SELECT … FROM alt`. Eine berechnete Spalte darf in
 * dieser Spaltenliste **nicht** vorkommen — SQLite lehnt das Schreiben ab
 * („cannot INSERT into generated column"), und der ganze Neubau scheitert. Sie
 * bleibt draussen und wird von der neuen Tabelle ausgerechnet.
 *
 * Die Herkunft wird hier als Doppel gesetzt: Gegenstand dieser Spec ist das
 * Anwenden, nicht der Herkunftsmechanismus — der hat seine eigenen Tests.
 */
class SqliteComputedExpressionChangeIntegrationTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.register(SqliteDriver()) }

    fun newPool(): ConnectionPool = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null, port = null, database = ":memory:", user = null, password = null,
        ),
    )

    fun schemaWith(expression: String, stored: Boolean = true) = SchemaDefinition(
        name = "sqlite_computed_change", version = "1",
        tables = mapOf(
            "order_line" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "quantity" to ColumnDefinition(NeutralType.Integer, required = true),
                    "unit_price" to ColumnDefinition(NeutralType.Float(), required = true),
                    "line_total" to ColumnDefinition(
                        NeutralType.Float(),
                        generation = ColumnGeneration.Computed(expression, stored = stored),
                    ),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    /** Ein `schema migrate --execute`-Lauf; `authorChanged` steht fuer das Urteil der Herkunft. */
    fun migrate(pool: ConnectionPool, want: SchemaDefinition, authorChanged: Boolean): List<String> {
        val tmp = createTempDirectory("sqlite-computed-change")
        return try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
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
                targetAwareComparator = { l, r, projection, _, serverForm ->
                    SchemaComparator(
                        projection,
                        RawTextAuthorship { _, _, _, _, _ -> authorChanged },
                        serverForm,
                    ).compare(l, r)
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
            val report = runCatching { java.nio.file.Files.readString(tmp.resolve("report.json")) }.getOrElse { "" }
            withClue(errors.joinToString("; ") + " || " + report) { exit shouldBe 0 }
            executed
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun tableSql(pool: ConnectionPool): String = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='order_line'")
                .use { rs -> if (rs.next()) rs.getString(1) else "" }
        }
    }

    fun insertAndRead(pool: ConnectionPool, id: Int, quantity: Int, price: String): Double? =
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use {
                it.execute("INSERT INTO order_line (id, quantity, unit_price) VALUES ($id, $quantity, $price)")
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT line_total FROM order_line WHERE id = $id")
                    .use { rs -> if (rs.next()) rs.getDouble(1) else null }
            }
        }

    test("a changed expression is applied and the rebuilt table computes by the new formula") {
        val pool = newPool()
        try {
            migrate(pool, schemaWith("quantity * unit_price"), authorChanged = false)
            insertAndRead(pool, id = 1, quantity = 3, price = "7.0") shouldBe 21.0

            val statements = migrate(pool, schemaWith("quantity * unit_price * 2"), authorChanged = true)

            withClue(statements.joinToString("; ")) {
                // Kein `ALTER COLUMN` — der Weg ist der Neubau.
                statements.any { it.contains("CREATE TABLE") } shouldBe true
                // Und die berechnete Spalte steht in keiner INSERT-Spaltenliste.
                statements.none { it.contains("INSERT INTO") && it.contains("line_total") } shouldBe true
            }
            withClue(tableSql(pool)) { tableSql(pool) shouldContain "quantity * unit_price * 2" }
            insertAndRead(pool, id = 2, quantity = 3, price = "7.0") shouldBe 42.0
        } finally {
            pool.close()
        }
    }

    test("the rows that were there survive the rebuild, recomputed") {
        val pool = newPool()
        try {
            migrate(pool, schemaWith("quantity * unit_price"), authorChanged = false)
            insertAndRead(pool, id = 1, quantity = 3, price = "7.0") shouldBe 21.0

            migrate(pool, schemaWith("quantity * unit_price * 2"), authorChanged = true)

            val stored = pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT line_total FROM order_line WHERE id = 1")
                        .use { rs -> if (rs.next()) rs.getDouble(1) else null }
                }
            }
            withClue("die Zeile ist noch da und traegt den neu gerechneten Wert") { stored shouldBe 42.0 }
        } finally {
            pool.close()
        }
    }

    test("a second migrate against the converged expression plans nothing") {
        val pool = newPool()
        try {
            migrate(pool, schemaWith("quantity * unit_price"), authorChanged = false)

            val again = migrate(pool, schemaWith("quantity * unit_price"), authorChanged = false)

            withClue(again.joinToString("; ")) { again.size shouldBe 0 }
        } finally {
            pool.close()
        }
    }

    test("a stored computed column added to a filled table goes through the rebuild") {
        val pool = newPool()
        try {
            val plain = SchemaDefinition(
                name = "sqlite_computed_change", version = "1",
                tables = mapOf("order_line" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "quantity" to ColumnDefinition(NeutralType.Integer, required = true),
                        "unit_price" to ColumnDefinition(NeutralType.Float(), required = true),
                    ),
                    primaryKey = listOf("id"),
                )),
            )
            migrate(pool, plain, authorChanged = false)
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use {
                    it.execute("INSERT INTO order_line (id, quantity, unit_price) VALUES (1, 3, 7.0)")
                }
            }

            val statements = migrate(pool, schemaWith("quantity * unit_price"), authorChanged = false)

            withClue(statements.joinToString("; ")) {
                statements.none { it.contains("ADD COLUMN") } shouldBe true
                statements.any { it.contains("CREATE TABLE") } shouldBe true
            }
            val value = pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT line_total FROM order_line WHERE id = 1")
                        .use { rs -> if (rs.next()) rs.getDouble(1) else null }
                }
            }
            withClue("die bestehende Zeile bekommt ihren gerechneten Wert") { value shouldBe 21.0 }
        } finally {
            pool.close()
        }
    }

    test("what SQLite accepts as ADD COLUMN, measured") {
        // Entscheidet, ob das Hinzufuegen einer berechneten Spalte den Neubau
        // braucht oder als schlichtes `ALTER TABLE ADD COLUMN` durchgeht —
        // einmal auf der leeren und einmal auf der gefuellten Tabelle, weil
        // eine gespeicherte Spalte in jeder vorhandenen Zeile Platz braucht.
        val pool = newPool()
        try {
            pool.borrow().asJdbc().use { conn ->
                fun attempt(sql: String) = runCatching {
                    conn.createStatement().use { it.execute(sql) }
                }.exceptionOrNull()?.message?.lineSequence()?.first()

                conn.createStatement().use { it.execute("CREATE TABLE empty_probe (a INTEGER)") }
                conn.createStatement().use { it.execute("CREATE TABLE full_probe (a INTEGER)") }
                conn.createStatement().use { it.execute("INSERT INTO full_probe (a) VALUES (21)") }

                val virtualOnEmpty =
                    attempt("ALTER TABLE empty_probe ADD COLUMN v INTEGER GENERATED ALWAYS AS (a * 2) VIRTUAL")
                val storedOnEmpty =
                    attempt("ALTER TABLE empty_probe ADD COLUMN s INTEGER GENERATED ALWAYS AS (a * 3) STORED")
                val virtualOnFull =
                    attempt("ALTER TABLE full_probe ADD COLUMN v INTEGER GENERATED ALWAYS AS (a * 2) VIRTUAL")
                val storedOnFull =
                    attempt("ALTER TABLE full_probe ADD COLUMN s INTEGER GENERATED ALWAYS AS (a * 3) STORED")

                withClue("virtuell, leer: $virtualOnEmpty") { (virtualOnEmpty == null) shouldBe true }
                withClue("gespeichert, leer: $storedOnEmpty") { (storedOnEmpty == null) shouldBe true }
                withClue("virtuell, gefuellt: $virtualOnFull") { (virtualOnFull == null) shouldBe true }
                // Der Fall, der den Neubau braucht.
                withClue("gespeichert, gefuellt: $storedOnFull") { (storedOnFull == null) shouldBe false }
            }
        } finally {
            pool.close()
        }
    }
})
