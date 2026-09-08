package dev.dmigrate.driver.mssql

import dev.dmigrate.cli.commands.PartitionOverlayHint
import dev.dmigrate.cli.commands.SchemaGenerateHelpers
import dev.dmigrate.cli.commands.SchemaGenerateRequest
import dev.dmigrate.cli.commands.SchemaGenerateRunner
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
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * Eine LIST-Partitionierung auf SQL Server — gegen einen echten Server.
 *
 * SQL Server kennt kein LIST. Mit der Zuordnung des Anwenders wird daraus
 * RANGE, und die Frage, die kein Unit-Test beantwortet, ist die einzige, auf
 * die es ankommt: **landet eine Zeile in der Partition, die ihre Wertemenge
 * versprochen hat?** Das sagt nur `$PARTITION`, und nur der Server.
 *
 * Zugleich die Gegenprobe zur Weitung: `9` stand in keiner LIST-Menge und
 * waere dort zurueckgewiesen worden. RANGE nimmt es an — in der
 * Auffang-Partition, die die Uebersetzung eigens im Modell fuehrt.
 */
class MssqlListPartitionOverlayIntegrationTest : FunSpec({

    val container = startMssqlContainer()
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = poolFor(container, "dmigrate_list_partitions")
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    val schema = SchemaDefinition(
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

    fun overlay(fingerprint: String) = MigrationOverlayDocument(
        source = "overlays/regions.json",
        overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.PARTITION_MAPPING,
            binding = MigrationOverlayBinding.Representation(fingerprint),
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

    fun generate(overlays: List<MigrationOverlayDocument>): Pair<Int, DdlResult?> {
        var captured: DdlResult? = null
        val runner = SchemaGenerateRunner(
            schemaReader = { schema },
            validator = { ValidationResult() },
            generatorLookup = { MssqlDdlGenerator() },
            reportWriter = { _, result, _, _, _, _, _ -> captured = result },
            fileWriter = { _, _ -> },
            formatJsonOutput = SchemaGenerateHelpers::formatJsonOutput,
            sidecarPath = SchemaGenerateHelpers::sidecarPath,
            rollbackPath = SchemaGenerateHelpers::rollbackPath,
            splitPath = SchemaGenerateHelpers::splitPath,
            printError = { _, _ -> },
            printValidationResult = { _, _, _ -> },
        )
        val exit = runner.execute(
            SchemaGenerateRequest(
                source = Path.of("/tmp/shop.yaml"),
                target = "mssql",
                migrationOverlays = overlays,
                output = null,
                report = Path.of("/tmp/report.json"),
                generateRollback = false,
                outputFormat = "plain",
                verbose = false,
                quiet = true,
            ),
        )
        return exit to captured
    }

    /** In welcher Partition die Zeile mit diesem Wert liegt — die Frage an den Server. */
    fun partitionOf(value: Int): Int = pool.borrow().asJdbc().use { conn ->
        conn.prepareStatement("SELECT \$PARTITION.pf_sales(?)").use { stmt ->
            stmt.setInt(1, value)
            stmt.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    test("the value sets become boundaries that route as promised") {
        val fingerprint = PartitionOverlayHint.representationFingerprint(schema, DatabaseDialect.MSSQL)
        val (exit, result) = generate(listOf(overlay(fingerprint)))
        exit shouldBe 0

        val statements = result!!.statements.map { it.sql }
        withClue(statements.toString()) {
            statements.any { it.contains("CREATE PARTITION FUNCTION") && it.contains("RANGE RIGHT") } shouldBe true
        }
        // Der Lauf sagt, dass er uebersetzt hat — sonst saehe der Anwender
        // RANGE-DDL fuer ein Schema, das LIST sagt.
        result.notes.single { it.code == PartitionOverlayHint.LIST_RENDERED_AS_RANGE }
            .message shouldContain "p_east_above"

        // Der Server nimmt das erzeugte DDL, wie es ist.
        execDdl(pool, *statements.toTypedArray())

        // Die eigentliche Frage: routet es wie die Wertemengen versprachen?
        listOf(1, 2).map { partitionOf(it) } shouldContainExactly listOf(1, 1)
        listOf(5, 6).map { partitionOf(it) } shouldContainExactly listOf(2, 2)

        // Und die Weitung ist echt: 9 stand in keiner Menge und hat trotzdem
        // eine Partition — die dritte, die das Modell eigens fuehrt.
        partitionOf(9) shouldBe 3

        // Die Grenzwerte selbst: `RANGE RIGHT` ist nicht Geschmack, sondern
        // die einzige Richtung, die das halboffene Intervall `[from, to)` des
        // Modells trifft. Bei LEFT gehoerte `3` noch zu 'p_west' — und damit
        // laege ein Wert in einer Partition, deren Wertemenge ihn nicht nennt.
        partitionOf(3) shouldBe 2
        partitionOf(7) shouldBe 3

        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO sales (region_id, amount) VALUES (1, 10), (6, 20), (9, 30)")
            }
        }
        val perPartition = pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT \$PARTITION.pf_sales(region_id) AS p, COUNT(*) AS n FROM sales GROUP BY " +
                        "\$PARTITION.pf_sales(region_id) ORDER BY p",
                ).use { rs ->
                    buildList { while (rs.next()) add(rs.getInt("p") to rs.getInt("n")) }
                }
            }
        }
        perPartition shouldContainExactly listOf(1 to 1, 2 to 1, 3 to 1)
    }

    test("without the overlay the table is created unpartitioned, and the run says what would fix it") {
        val (exit, result) = generate(emptyList())
        exit shouldBe 0

        val notes = result!!.notes
        withClue(notes.map { it.code }.toString()) {
            notes.any { it.code == "E055" } shouldBe true
        }
        val hint = notes.single { it.code == PartitionOverlayHint.LIST_MAPPING_AVAILABLE }
        hint.hint!! shouldContain PartitionOverlayHint.representationFingerprint(schema, DatabaseDialect.MSSQL)
    }
})
