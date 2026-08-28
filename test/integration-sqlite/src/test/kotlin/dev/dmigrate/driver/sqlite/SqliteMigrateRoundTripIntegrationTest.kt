package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.connection.asJdbc

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.SchemaRollbackRequest
import dev.dmigrate.cli.commands.SchemaRollbackRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createTempDirectory


/**
 * F.4 — SQLite round-trip smoke (`docs/planning/done-archive/diffresult-migration-plan.md §F.4`).
 *
 * Two scenarios in one spec — the plan's two F.4 sub-bullets:
 *
 * 1. **AddColumn direct path**: mirrors F.2 / F.3 against in-memory
 *    SQLite. AddColumn renders to the simple `ALTER TABLE ADD COLUMN`
 *    form (no rebuild); DropColumn (Down) goes through `ALTER TABLE
 *    DROP COLUMN` on SQLite ≥3.35. Verifies that the generic runner
 *    pipeline works against SQLite without the rebuild machinery in
 *    play.
 *
 * 2. **AlterColumnNullability rebuild path**: forces the
 *    `SqliteRebuildRenderer` 9-statement sequence (`PRAGMA
 *    foreign_keys = OFF` → `BEGIN IMMEDIATE` → CREATE temp / INSERT-
 *    SELECT / DROP / RENAME / index recreation → `PRAGMA
 *    foreign_key_check` → `COMMIT` → `PRAGMA foreign_keys = ON`)
 *    against a real SQLite engine. The rebuild emits its OWN
 *    transaction control; the executor's `autoCommit = false` setup
 *    must not collide with the explicit `BEGIN IMMEDIATE` (renderer
 *    docstring at `SqliteRebuildRenderer.kt:45-62`).
 *
 * In-memory SQLite via `HikariConnectionPoolFactory` with
 * `database = ":memory:"`: the factory enforces `maximumPoolSize = 1`
 * for SQLite so the same connection is reused across borrows, keeping
 * the in-memory DB alive for the duration of the test.
 */
class SqliteMigrateRoundTripIntegrationTest : FunSpec({


    fun newPool(): ConnectionPool = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null,
            port = null,
            database = ":memory:",
            user = null,
            password = null,
        ),
    )

    test("a changed index is dropped before it is recreated") {
        // Bei SQLite sind Primaerschluessel- und Constraint-Aenderungen
        // Rebuild-Ausloeser und werden absorbiert: die Tabelle wird aus der
        // Zieldefinition neu gebaut, die Add/Drop-Reihenfolge erreicht das SQL
        // nie. Index-Operationen werden ausdruecklich NICHT absorbiert
        // (SqliteRebuildPlanner.isAbsorbedByRebuild) — hier greift die
        // Ordnungskante, und ohne sie liefe `CREATE INDEX` auf einen Namen, den
        // der nachfolgende `DROP` erst freigeben sollte.
        val pool = newPool()
        val tmp = createTempDirectory("sqlite-replace-order")
        try {
            execDdl(
                pool,
                "CREATE TABLE replace_order (id INTEGER PRIMARY KEY, code TEXT, tenant INTEGER)",
                "CREATE INDEX ix_code ON replace_order (code)",
            )
            val desired = SchemaDefinition(
                name = "replace-order", version = "1",
                tables = mapOf(
                    "replace_order" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer),
                            "code" to ColumnDefinition(NeutralType.Text()),
                            "tenant" to ColumnDefinition(NeutralType.Integer),
                        ),
                        primaryKey = listOf("id"),
                        indices = listOf(IndexDefinition(
                            name = "ix_code",
                            columns = listOf(IndexColumn("code"), IndexColumn("tenant")),
                        )),
                    ),
                ),
            )

            val errors = mutableListOf<String>()
            val exit = sqliteMigrateRunner(pool, desired, errors).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.SQLITE,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            withClue("meldungen:\n" + errors.joinToString("\n")) { exit shouldBe 0 }

            // Gegenprobe im Katalog: der Index traegt jetzt beide Spalten.
            val definition = pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='index' AND name='ix_code'")
                        .use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }
            withClue("index-definition: $definition") {
                definition.shouldNotBeNull() shouldContain "tenant"
            }
        } finally {
            tmp.toFile().deleteRecursively()
            pool.close()
        }
    }

    test("F.4.a — AddColumn round-trip via direct ALTER TABLE (no rebuild)") {
        val pool = newPool()
        val tmp = createTempDirectory("sqlite-roundtrip-f4a")
        try {
            execDdl(
                pool,
                "CREATE TABLE round_trip (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            )

            val originalSchema = SchemaDefinition(
                name = "rt-original",
                version = "0",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = false),
                            "name" to ColumnDefinition(NeutralType.Text(), required = true),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )
            val desiredSchema = SchemaDefinition(
                name = "rt-desired",
                version = "1",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = false),
                            "name" to ColumnDefinition(NeutralType.Text(), required = true),
                            "email" to ColumnDefinition(NeutralType.Text()),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )

            MigrationFingerprint.compute(readSqliteSchema(pool)) shouldBe
                MigrationFingerprint.compute(originalSchema)

            val rollbackPath = tmp.resolve("rollback.sql")
            val reportPath = tmp.resolve("report.json")

            val errors = mutableListOf<String>()
            val migrateRunner = sqliteMigrateRunner(pool, desiredSchema, errors)
            val migrateExit = migrateRunner.execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.SQLITE,
                    report = reportPath,
                    rollbackOutput = rollbackPath,
                    generateRollback = true,
                    execute = true,
                ),
            )
            withClue({ "migrate errors: $errors" }) { migrateExit shouldBe 0 }
            Files.exists(rollbackPath) shouldBe true
            Files.exists(reportPath) shouldBe true
            val reportText = Files.readString(reportPath)
            reportText shouldContain "status=ok"
            reportText shouldContain "exitCode=0"

            MigrationFingerprint.compute(readSqliteSchema(pool)) shouldBe
                MigrationFingerprint.compute(desiredSchema)

            val rollbackRunner = sqliteRollbackRunner(pool, errors)
            withClue({ "rollback errors: $errors" }) {
                rollbackRunner.execute(
                    SchemaRollbackRequest(
                        source = rollbackPath,
                        target = "db:placeholder",
                        execute = true,
                        allowDestructive = true,
                    ),
                ) shouldBe 0
            }

            MigrationFingerprint.compute(readSqliteSchema(pool)) shouldBe
                MigrationFingerprint.compute(originalSchema)
        } finally {
            tmp.toFile().deleteRecursively()
            pool.close()
        }
    }

    test("F.6.c — mid-rebuild failure (NOT NULL violation in INSERT-SELECT) leaves the original table pristine") {
        // Per Plan §F.6.c: the SQLite RebuildTable pipeline must run
        // as one indivisible unit. Force an INSERT-SELECT failure by
        // pre-populating the source table with a NULL row that the
        // target schema's NOT NULL column would reject. The stream-
        // owned transaction model wraps the entire pipeline in
        // `BEGIN IMMEDIATE` … `ROLLBACK` (on error); after the
        // rollback the live database must be byte-equal to the
        // pre-migrate state — original table intact, original row
        // intact, and no orphan `<table>__dmg_rebuild_*` left behind.
        val pool = newPool()
        val tmp = createTempDirectory("sqlite-roundtrip-f6c")
        try {
            execDdl(
                pool,
                "CREATE TABLE round_trip (id INTEGER PRIMARY KEY, status TEXT)",
                "INSERT INTO round_trip (id, status) VALUES (1, NULL)",
            )

            val originalSchema = SchemaDefinition(
                name = "rt-original",
                version = "0",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = false),
                            "status" to ColumnDefinition(NeutralType.Text(), required = false),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )
            // desired flips status → NOT NULL — the rebuild's INSERT
            // SELECT will fail on the NULL row in the temp table.
            val desiredSchema = SchemaDefinition(
                name = "rt-desired",
                version = "1",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = false),
                            "status" to ColumnDefinition(NeutralType.Text(), required = true),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )

            val pristineFingerprint = MigrationFingerprint.compute(readSqliteSchema(pool))
            pristineFingerprint shouldBe MigrationFingerprint.compute(originalSchema)
            countRoundTripRows(pool) shouldBe 1

            val reportPath = tmp.resolve("report.json")
            val errors = mutableListOf<String>()

            val migrateRunner = sqliteMigrateRunner(pool, desiredSchema, errors)
            val migrateExit = migrateRunner.execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.SQLITE,
                    report = reportPath,
                    execute = true,
                ),
            )
            // Exit 5 — execute path with executionError. Renderer gave us
            // a clean plan; the failure is at runtime inside the rebuild.
            withClue({
                val report = if (Files.exists(reportPath)) Files.readString(reportPath) else "<no report>"
                "migrate errors: $errors\nreport: $report"
            }) { migrateExit shouldBe 5 }

            val report = Files.readString(reportPath)
            // Stream-owned model handled the rollback explicitly via a
            // literal `ROLLBACK;` against the live SQLite connection —
            // pin the trace fields so a future executor refactor can't
            // silently flip atomicity into "half-applied".
            report shouldContain "transactionRolledBack=true"
            report shouldContain "sideEffectsPossible=false"
            report shouldContain "executionError="

            // Schema-level invariant: post-failure fingerprint matches
            // the pre-migrate snapshot bit-for-bit. Any drift here
            // would mean the rebuild left structural debris.
            MigrationFingerprint.compute(readSqliteSchema(pool)) shouldBe pristineFingerprint

            // Row-level invariant: the NULL row that triggered the
            // INSERT-SELECT failure is still there, untouched.
            countRoundTripRows(pool) shouldBe 1

            // No `__dmg_rebuild_*` orphan table — the BEGIN IMMEDIATE …
            // ROLLBACK reverted the CREATE TABLE for the temp.
            listOrphanRebuildTables(pool).shouldBeEmpty()
        } finally {
            tmp.toFile().deleteRecursively()
            pool.close()
        }
    }

    test("F.4.b — AlterColumnNullability round-trip via SQLite RebuildTable pipeline") {
        val pool = newPool()
        val tmp = createTempDirectory("sqlite-roundtrip-f4b")
        try {
            // Empty table — the rebuild's INSERT-SELECT will copy zero rows,
            // so a nullable→NOT NULL transition is safe even though the
            // existing column has no DEFAULT.
            execDdl(
                pool,
                "CREATE TABLE round_trip (id INTEGER PRIMARY KEY, status TEXT)",
            )

            val originalSchema = SchemaDefinition(
                name = "rt-original",
                version = "0",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = false),
                            "status" to ColumnDefinition(NeutralType.Text(), required = false),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )
            val desiredSchema = SchemaDefinition(
                name = "rt-desired",
                version = "1",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = false),
                            "status" to ColumnDefinition(NeutralType.Text(), required = true),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )

            MigrationFingerprint.compute(readSqliteSchema(pool)) shouldBe
                MigrationFingerprint.compute(originalSchema)

            val rollbackPath = tmp.resolve("rollback.sql")
            val reportPath = tmp.resolve("report.json")
            val errors = mutableListOf<String>()

            val migrateRunner = sqliteMigrateRunner(pool, desiredSchema, errors)
            val migrateExit = migrateRunner.execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.SQLITE,
                    report = reportPath,
                    rollbackOutput = rollbackPath,
                    generateRollback = true,
                    execute = true,
                ),
            )
            withClue({
                val report = if (Files.exists(reportPath)) Files.readString(reportPath) else "<no report>"
                "migrate errors: $errors\nreport: $report"
            }) { migrateExit shouldBe 0 }
            Files.exists(rollbackPath) shouldBe true
            // Sanity: the rendered Up SQL actually went through the rebuild
            // path. The rollback artefact carries the symmetric Down sequence,
            // so the same markers must show up there too.
            val artefactText = Files.readString(rollbackPath)
            artefactText shouldContain "BEGIN IMMEDIATE"
            artefactText shouldContain "PRAGMA foreign_key_check"

            MigrationFingerprint.compute(readSqliteSchema(pool)) shouldBe
                MigrationFingerprint.compute(desiredSchema)

            val rollbackRunner = sqliteRollbackRunner(pool, errors)
            withClue({ "rollback errors: $errors" }) {
                rollbackRunner.execute(
                    SchemaRollbackRequest(
                        source = rollbackPath,
                        target = "db:placeholder",
                        execute = true,
                        allowDestructive = true,
                    ),
                ) shouldBe 0
            }

            MigrationFingerprint.compute(readSqliteSchema(pool)) shouldBe
                MigrationFingerprint.compute(originalSchema)
        } finally {
            tmp.toFile().deleteRecursively()
            pool.close()
        }
    }
})

private fun countRoundTripRows(pool: ConnectionPool): Int {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM round_trip").use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }
}

private fun listOrphanRebuildTables(pool: ConnectionPool): List<String> {
    val out = mutableListOf<String>()
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%__dmg_rebuild_%'",
            ).use { rs ->
                while (rs.next()) out += rs.getString(1)
            }
        }
    }
    return out
}

private fun execDdl(pool: ConnectionPool, vararg sqls: String) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            for (sql in sqls) stmt.execute(sql)
        }
    }
}

private fun readSqliteSchema(pool: ConnectionPool): SchemaDefinition =
    SqliteSchemaReader().read(pool, SchemaReadOptions()).schema

private fun liveOperand(pool: ConnectionPool): ResolvedSchemaOperand = ResolvedSchemaOperand(
    reference = "live-sqlite",
    schema = readSqliteSchema(pool),
    validation = ValidationResult(),
    dialect = DatabaseDialect.SQLITE,
)

private fun sqliteMigrateRunner(
    pool: ConnectionPool,
    desiredSchema: SchemaDefinition,
    errorSink: MutableList<String> = mutableListOf(),
): SchemaMigrateRunner = SchemaMigrateRunner(
    fileLoader = { _ ->
        ResolvedSchemaOperand(
            reference = "desired",
            schema = desiredSchema,
            validation = ValidationResult(),
        )
    },
    dbLoader = { _, _ -> liveOperand(pool) },
    comparator = { a, b -> SchemaComparator().compare(a, b) },
    rendererFor = { d ->
        if (d == DatabaseDialect.SQLITE) SqliteDiffDdlGenerator() else null
    },
    executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
    renderReport = { r, _ -> r.toString() },
    printError = { msg, src -> errorSink += "[$src] $msg" },
)

private fun sqliteRollbackRunner(
    pool: ConnectionPool,
    errorSink: MutableList<String> = mutableListOf(),
): SchemaRollbackRunner = SchemaRollbackRunner(
    dbLoader = { _, _ -> liveOperand(pool) },
    executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
    printError = { msg, src -> errorSink += "[$src] $msg" },
)
