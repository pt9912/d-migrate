package dev.dmigrate.driver.postgresql

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
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import kotlin.io.path.createTempDirectory


/**
 * F.2 — PostgreSQL round-trip smoke (`docs/planning/done-archive/diffresult-migration-plan.md §F.2`).
 *
 * Drives `SchemaMigrateRunner` and `SchemaRollbackRunner` end-to-end
 * against a live PostgreSQL via Testcontainers, exercising the pipeline
 * the CLI also wires up:
 *
 * 1. set up Ausgangsschema in the DB
 * 2. `schema migrate --execute --generate-rollback --rollback-output …`
 *    (the runner's own post-compare must succeed → exit 0, artefact
 *    written)
 * 3. **independent** reverse + content-fingerprint compare against the
 *    desired Soll-schema — verifies the runner's post-compare from the
 *    test's vantage point
 * 4. `schema rollback --execute --allow-destructive` against the same
 *    DB using the artefact from step 2
 * 5. **independent** reverse + content-fingerprint compare against the
 *    Ausgangsschema
 *
 * The Soll/Ist comparison uses [MigrationFingerprint.compute], which is
 * a content-only hash (no `name`/`version` projection) — see the
 * fingerprint docstring. That makes the smoke immune to the test-side
 * label choice and consistent with how the rollback runner verifies
 * `TARGET_STATE_MISMATCH`.
 */
class PostgresMigrateRoundTripIntegrationTest : FunSpec({


    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var config: ConnectionConfig
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        config = ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = container.host,
            port = container.firstMappedPort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
        )
        pool = HikariConnectionPoolFactory.create(config)
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("a reshaped primary key and a changed UNIQUE are dropped before they are added") {
        // Beide Paare stehen in derselben Phase und tragen denselben Objektnamen.
        // Ohne Ordnungskante legt der Plan sie an, bevor er die alten verwirft —
        // PostgreSQL antwortet dann mit 42P16 (Primaerschluessel) bzw. 42710
        // (Constraint), und der Lauf endet nicht mit 0.
        val tmp = createTempDirectory("pg-replace-order")
        try {
            execDdl(
                pool,
                "DROP TABLE IF EXISTS replace_order",
                // Der Primaerschluessel bleibt ungenannt: PostgreSQL vergibt dann
                // `replace_order_pkey`, und nur diesen Konventionsnamen kann der
                // Diff-Pfad verwerfen (PG_PK_NAME_CONVENTION). Ein abweichender Name
                // ist eine eigene, dokumentierte Grenze und wuerde hier nur den
                // Ordnungsbeweis verdecken.
                // Beide Seiten mehrspaltig: einspaltige UNIQUE-Constraints hebt der
                // Reverse auf `column.unique` und der Vergleich synthetisiert einen
                // Namen — daraus wuerden zwei verschiedene Objekte statt eines
                // geaenderten Paares, und die Ordnungskante griffe gar nicht.
                "CREATE TABLE replace_order (id BIGINT NOT NULL, tenant BIGINT NOT NULL, code TEXT, " +
                    "PRIMARY KEY (id), CONSTRAINT uq_code UNIQUE (code, tenant))",
            )

            // UNIQUE statt CHECK: dessen Round-Trip ist strukturell. Ein CHECK
            // kaeme aus PostgreSQL als `((total > 0))` zurueck und der Post-Compare
            // meldete eine Textabweichung — eine eigene Sache, die hier nur den
            // Ordnungsbeweis verdecken wuerde.
            val desired = SchemaDefinition(
                name = "replace-order", version = "1",
                tables = mapOf(
                    "replace_order" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "tenant" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "code" to ColumnDefinition(NeutralType.Text()),
                        ),
                        primaryKey = listOf("id", "tenant"),
                        constraints = listOf(ConstraintDefinition(
                            name = "uq_code", type = ConstraintType.UNIQUE, columns = listOf("code", "id"),
                        )),
                    ),
                ),
            )

            val errors = mutableListOf<String>()
            val executed = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                rendererFor = { d -> if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.POSTGRESQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            // Exit 5 waere der Ausfuehrungsfehler — genau das Symptom der falschen
            // Reihenfolge. Geprueft wird deshalb zuerst, dass der Server die
            // Anweisungen angenommen hat.
            withClue(
                "ausgefuehrt:\n" + executed.joinToString("\n") +
                    "\nmeldungen:\n" + errors.joinToString("\n"),
            ) { exit shouldBe 0 }

            // Und dass beide Paare in der richtigen Reihenfolge standen.
            val dropPk = executed.indexOfFirst { it.contains("DROP CONSTRAINT") && it.contains("_pkey") }
            val addPk = executed.indexOfFirst { it.contains("ADD PRIMARY KEY") }
            val dropCk = executed.indexOfFirst { it.contains("DROP CONSTRAINT") && it.contains("uq_code") }
            val addCk = executed.indexOfFirst { it.contains("ADD CONSTRAINT") && it.contains("uq_code") }
            withClue("ausgefuehrt:\n" + executed.joinToString("\n")) {
                (dropPk >= 0 && addPk > dropPk) shouldBe true
                (dropCk >= 0 && addCk > dropCk) shouldBe true
            }

            // Gegenprobe im Katalog: der Schluessel traegt jetzt beide Spalten.
            val pkColumns = pool.borrow().asJdbc().use { conn ->
                conn.prepareStatement(
                    """
                    SELECT a.attname FROM pg_index i
                      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                      WHERE i.indrelid = 'replace_order'::regclass AND i.indisprimary
                      ORDER BY a.attnum
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
            pkColumns shouldBe listOf("id", "tenant")
        } finally {
            execDdl(pool, "DROP TABLE IF EXISTS replace_order")
            tmp.toFile().deleteRecursively()
        }
    }

    test("AddColumn round-trip leaves Ist == Ausgangsschema (content fingerprints match)") {
        val tmp = createTempDirectory("pg-roundtrip-f2")
        try {
            // ── 1. Ausgangsschema in der DB einrichten ──────────────────
            execDdl(
                pool,
                "DROP TABLE IF EXISTS round_trip",
                "CREATE TABLE round_trip (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)",
            )

            val originalSchema = SchemaDefinition(
                name = "rt-original",
                version = "0",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
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
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
                            "email" to ColumnDefinition(NeutralType.Text(maxLength = 200)),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )

            // Sanity: Ausgangs-DB matches our Ausgangsschema content.
            MigrationFingerprint.compute(readPgSchema(pool)) shouldBe
                MigrationFingerprint.compute(originalSchema)

            // ── 2. schema migrate --execute --generate-rollback ─────────
            val rollbackPath = tmp.resolve("rollback.sql")
            val reportPath = tmp.resolve("report.json")

            val migrateRunner = SchemaMigrateRunner(
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
                    if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null
                },
                executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            )

            val migrateExit = migrateRunner.execute(
                SchemaMigrateRequest(
                    // CompareOperandParser requires a `file:` prefix; the path itself is
                    // ignored because our fileLoader returns the in-code desired schema.
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.POSTGRESQL,
                    report = reportPath,
                    rollbackOutput = rollbackPath,
                    generateRollback = true,
                    execute = true,
                ),
            )
            migrateExit shouldBe 0
            Files.exists(rollbackPath) shouldBe true
            Files.exists(reportPath) shouldBe true
            // Sanity-spot-check that the report carries the expected
            // post-execute state. The renderer is the trivial
            // `SchemaMigrateReport.toString()` (see runner wiring above),
            // so we look at substrings rather than parsing JSON.
            val reportText = Files.readString(reportPath)
            reportText shouldContain "status=ok"
            reportText shouldContain "exitCode=0"
            reportText shouldContain "started=true"
            reportText shouldContain "executionError=null"

            // ── 3. Reverse + Compare gegen Ziel-Schema ──────────────────
            MigrationFingerprint.compute(readPgSchema(pool)) shouldBe
                MigrationFingerprint.compute(desiredSchema)

            // ── 4. schema rollback --execute --allow-destructive ────────
            val rollbackRunner = SchemaRollbackRunner(
                dbLoader = { _, _ -> liveOperand(pool) },
                executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            )
            val rollbackExit = rollbackRunner.execute(
                SchemaRollbackRequest(
                    source = rollbackPath,
                    target = "db:placeholder",
                    execute = true,
                    allowDestructive = true,
                ),
            )
            rollbackExit shouldBe 0

            // ── 5. Reverse + Compare gegen Ausgangsschema ───────────────
            MigrationFingerprint.compute(readPgSchema(pool)) shouldBe
                MigrationFingerprint.compute(originalSchema)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
})

private fun execDdl(pool: ConnectionPool, vararg sqls: String) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            for (sql in sqls) stmt.execute(sql)
        }
    }
}

private fun readPgSchema(pool: ConnectionPool): SchemaDefinition =
    PostgresSchemaReader().read(pool, SchemaReadOptions()).schema

private fun liveOperand(pool: ConnectionPool): ResolvedSchemaOperand = ResolvedSchemaOperand(
    reference = "live-pg",
    schema = readPgSchema(pool),
    validation = ValidationResult(),
    dialect = DatabaseDialect.POSTGRESQL,
)
