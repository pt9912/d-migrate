package dev.dmigrate.driver.oracle

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.SchemaRollbackRequest
import dev.dmigrate.cli.commands.SchemaRollbackRunner
import dev.dmigrate.cli.commands.capabilityGenerationCanonicalizer
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.DiffDdlGenerator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.testcontainers.oracle.OracleContainer
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.createTempDirectory

/**
 * Round-Trip-Beleg fuer `schema migrate` gegen ein ECHTES Oracle — das
 * Oracle-Gegenstueck zu `MssqlMigrateRoundTripIntegrationTest`.
 *
 * Bis hierher war der Oracle-Diff-Pfad ausschliesslich gegen Unit-Tests
 * und einen Datei-Modus-E2E belegt. Gefahren werden deshalb die ECHTEN
 * Runner, nicht der Renderer allein:
 *
 * 1. Ausgangsschema in der Datenbank einrichten,
 * 2. `schema migrate --execute --generate-rollback` (der Post-Compare des
 *    Runners muss selbst durchgehen → Exit 0, Artefakt geschrieben),
 * 3. **unabhaengig** zurueckliesen und gegen das Soll pruefen,
 * 4. `schema rollback --execute --allow-destructive` mit dem Artefakt,
 * 5. unabhaengig zurueckliesen und gegen das Ausgangsschema pruefen.
 *
 * Die Tabelle traegt bewusst eine **IDENTITY-Spalte**. Damit haengt der
 * Lauf an dem in Sub-Slice 5e-2 eingezogenen
 * `canonicalizeGeneration`-Hook: Oracle vergibt den Sequenznamen selbst
 * (`ISEQ$$_n`), das user-authored Soll-Schema kann ihn nicht kennen.
 * Verifiziert: setzt man `DialectCapabilities.namesIdentitySequences` fuer
 * Oracle auf `true`, faellt dieser Test. Der Post-Compare des Runners
 * rechnet mit derselben Projektion.
 *
 * Der Reverse legt eine IDENTITY-Spalte als **numerischen Typ plus
 * `generation`** ab, nicht als `Identifier(autoIncrement = true)`. Das Soll
 * folgt dem; die andere Schreibweise plant eine Typaenderung auf einer
 * unveraenderten Spalte, siehe
 * `docs/planning/open/identity-column-shape-mismatch.md`.
 */
class OracleMigrateRoundTripIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.ORACLE,
                host = container.host,
                port = container.oraclePort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun execDdl(vararg sqls: String) {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
        }
    }

    fun readSchema(): SchemaDefinition = OracleSchemaReader().read(pool).schema

    fun liveOperand(): ResolvedSchemaOperand = ResolvedSchemaOperand(
        reference = "live-oracle",
        schema = readSchema(),
        validation = ValidationResult(),
        dialect = DatabaseDialect.ORACLE,
    )

    /** Dieselben Projektionen, die der Migrate-Pfad fuer Oracle waehlt. */
    fun fingerprintOf(schema: SchemaDefinition) = MigrationFingerprint.compute(
        schema,
        canonicalizeType = { type ->
            OracleDriver().typeCanonicalizer().canonicalize(type, schema.customTypes)
        },
        canonicalizeGeneration = capabilityGenerationCanonicalizer(DatabaseDialect.ORACLE),
    )

    fun noRenderer(): DiffDdlGenerator = error("test wires only the Oracle renderer")

    test("AddColumn on an IDENTITY table round-trips and leaves the database as it started") {
        val tmp = createTempDirectory("oracle-roundtrip")
        try {
            execDdl(
                """CREATE TABLE "round_trip" (
                     "id" NUMBER(9) GENERATED ALWAYS AS IDENTITY
                       CONSTRAINT "pk_round_trip" PRIMARY KEY,
                     "name" VARCHAR2(100) NOT NULL
                   )""",
            )

            // Das Soll ist user-authored: es traegt KEINEN Sequenznamen.
            val original = SchemaDefinition(
                name = "rt-original", version = "0",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(
                                // Der Oracle-Reverse legt eine IDENTITY-Spalte als
                                // NUMERISCHEN Typ plus `generation` ab, nicht als
                                // `Identifier(autoIncrement)` -- das Soll folgt dem.
                                NeutralType.Integer,
                                generation = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS),
                            ),
                            "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )
            val desired = SchemaDefinition(
                name = "rt-desired", version = "1",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(
                                // Der Oracle-Reverse legt eine IDENTITY-Spalte als
                                // NUMERISCHEN Typ plus `generation` ab, nicht als
                                // `Identifier(autoIncrement)` -- das Soll folgt dem.
                                NeutralType.Integer,
                                generation = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS),
                            ),
                            "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
                            "email" to ColumnDefinition(NeutralType.Text(maxLength = 200)),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )

            // Vorbedingung -- und zugleich der Beleg, dass die Projektion
            // traegt: der Reverse liest hier einen Sequenznamen, das Soll
            // nicht.
            fingerprintOf(readSchema()) shouldBe fingerprintOf(original)
            // Gegenprobe: ohne die Projektion sind es zwei verschiedene
            // Abdruecke, und der Post-Compare unten schluege fehl.
            MigrationFingerprint.compute(readSchema()) shouldNotBe MigrationFingerprint.compute(original)

            val rollbackPath = tmp.resolve("rollback.sql")
            val reportPath = tmp.resolve("report.json")
            val errors = mutableListOf<String>()
            val migrateExit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand() },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.ORACLE) OracleDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
                renderReport = { r, _ -> r.toString().also { errors += it } },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.ORACLE,
                    report = reportPath,
                    rollbackOutput = rollbackPath,
                    generateRollback = true,
                    execute = true,
                ),
            )
            withClueErrors(errors) { migrateExit shouldBe 0 }
            Files.exists(rollbackPath) shouldBe true

            // Gegenprobe zum Post-Compare des Runners.
            fingerprintOf(readSchema()) shouldBe fingerprintOf(desired)

            val rollbackExit = SchemaRollbackRunner(
                dbLoader = { _, _ -> liveOperand() },
                executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaRollbackRequest(
                    source = rollbackPath,
                    target = "db:placeholder",
                    execute = true,
                    allowDestructive = true,
                ),
            )
            withClueErrors(errors) { rollbackExit shouldBe 0 }

            fingerprintOf(readSchema()) shouldBe fingerprintOf(original)
        } finally {
            runCatching { execDdl("""DROP TABLE "round_trip" PURGE""") }
            tmp.toFile().deleteRecursively()
        }
    }
})

/** Haengt die gesammelten Runner-Meldungen an eine fehlgeschlagene Zusicherung. */
private inline fun withClueErrors(errors: List<String>, block: () -> Unit) {
    try {
        block()
    } catch (t: AssertionError) {
        throw AssertionError("runner errors:\n" + errors.joinToString("\n") + "\n" + t.message, t)
    }
}
