package dev.dmigrate.driver.mssql

import dev.dmigrate.cli.commands.PartitionOverlayHint
import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayBinding
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.PartitionMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

/**
 * Ob ein mit Overlay erzeugtes Schema auch **wieder** migrierbar ist.
 *
 * Das ist die Frage, die den Generate-Pfad allein wertlos machen wuerde: das
 * Soll sagt LIST, der Reverse des Ziels sagt RANGE, und ohne dieselbe
 * Uebersetzung vor dem Vergleich meldete jeder Lauf dieselbe
 * Strategieaenderung — eine Migration, die gesund aussieht und nie fertig
 * wird.
 *
 * Gemessen wird gegen einen echten Server, weil nur er sagt, was der Reverse
 * wirklich zurueckgibt.
 */
class MssqlListPartitionMigrateIntegrationTest : FunSpec({

    val container = startMssqlContainer()
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = poolFor(container, "dmigrate_list_migrate")
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    val desired = SchemaDefinition(
        name = "shop",
        version = "1.0.0",
        tables = mapOf(
            "sales" to TableDefinition(
                columns = mapOf(
                    "region_id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                    "amount" to ColumnDefinition(type = NeutralType.Integer),
                ),
                partitioning = PartitionConfig(
                    type = PartitionType.LIST,
                    key = listOf("region_id"),
                    partitions = listOf(
                        PartitionDefinition(name = "p_west", values = listOf("1", "2")),
                        PartitionDefinition(name = "p_east", values = listOf("5", "6")),
                    ),
                ),
            ),
        ),
    )

    fun overlay() = MigrationOverlayDocument(
        source = "overlays/regions.json",
        overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.PARTITION_MAPPING,
            binding = MigrationOverlayBinding.Representation(
                PartitionOverlayHint.representationFingerprint(desired, DatabaseDialect.MSSQL),
            ),
            dialect = "mssql",
            entries = listOf(
                PartitionMappingOverlayEntry(
                    id = "w", table = "sales", sourcePartition = "p_west",
                    values = listOf("1", "2"), rangeUpperBound = "3",
                ),
                PartitionMappingOverlayEntry(
                    id = "e", table = "sales", sourcePartition = "p_east",
                    values = listOf("5", "6"), rangeUpperBound = "7",
                ),
            ),
            createdAt = "2026-09-08T12:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash(),
    )

    /** Ein `--plan-only`-Lauf gegen die laufende Datenbank. */
    fun planAgainstLive(overlays: List<MigrationOverlayDocument>): Triple<Int, Int, List<String>> {
        val tmp = createTempDirectory("mssql-list-migrate")
        val errors = mutableListOf<String>()
        var operations = -1
        var diagnostics: List<String> = emptyList()
        try {
            val exit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { MssqlDiffDdlGenerator() },
                executor = { _, _, _, _, _ -> error("plan-only must not execute") },
                renderReport = { r, _ ->
                    operations = r.operations.size
                    diagnostics = r.diagnostics.map { "${it.code}/${it.severity}: ${it.message}" }
                    r.toString()
                },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = tmp.resolve("report.json"),
                    planOnly = true,
                    migrationOverlays = overlays,
                ),
            )
            return Triple(exit, operations, errors + diagnostics)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    beforeSpec {
        // Die Tabelle so, wie der Generate-Pfad sie aus dem Overlay schreibt.
        execDdl(
            pool,
            "CREATE PARTITION FUNCTION pf_sales (INT) AS RANGE RIGHT FOR VALUES (3, 7)",
            "CREATE PARTITION SCHEME ps_sales AS PARTITION pf_sales ALL TO ([PRIMARY])",
            "CREATE TABLE sales (region_id INT NOT NULL, amount INT NULL) ON ps_sales(region_id)",
        )
    }

    test("with the same overlay the pair is equal — no leftover partitioning warning") {
        val (exit, operations, findings) = planAgainstLive(listOf(overlay()))

        withClue("exit=$exit befunde=$findings") { exit shouldBe 0 }
        withClue("geplante Operationen: $operations") { operations shouldBe 0 }
        withClue(findings.toString()) {
            findings.none { it.startsWith(PARTITIONING_NOT_APPLIED) } shouldBe true
        }
    }

    test("without the overlay the run keeps advising a manual rebuild of a table that is already right") {
        // Der gemessene Ist-Zustand ohne die Naht. Es sind **nicht** Drop/Create-
        // Paare, wie man erwarten koennte: der Planer emittiert fuer eine
        // Strategieaenderung gar keine Operation, sondern eine Warnung — und die
        // bleibt bei jedem Lauf stehen und raet, eine Tabelle von Hand neu zu
        // bauen, die in Wahrheit genau richtig ist.
        val (exit, operations, findings) = planAgainstLive(emptyList())

        exit shouldBe 0
        operations shouldBe 0
        withClue(findings.toString()) {
            findings.any { it.startsWith(PARTITIONING_NOT_APPLIED) } shouldBe true
        }
    }
})

/** Die Warnung, die eine nicht anwendbare Partitionierungsaenderung hinterlaesst. */
private const val PARTITIONING_NOT_APPLIED = "PARTITIONING_CHANGE_NOT_APPLIED"
