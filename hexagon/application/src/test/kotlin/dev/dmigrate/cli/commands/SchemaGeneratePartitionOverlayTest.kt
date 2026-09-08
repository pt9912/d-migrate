package dev.dmigrate.cli.commands

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
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * Was `schema generate` aus einer LIST-Partitionierung macht, wenn der
 * Zieldialekt LIST nicht kennt.
 *
 * SQL Server partitioniert ausschliesslich nach RANGE-Grenzen. Eine
 * LIST-Partitionierung ist genau dann als RANGE ausdrueckbar, wenn die
 * Wertemengen zusammenhaengend liegen — **welche** Grenze zu welcher Menge
 * gehoert, sagt der Anwender ueber ein Overlay. Ohne eines bleibt es bei
 * `E055`, und die Meldung sagt jetzt, was beizusteuern waere.
 */
class SchemaGeneratePartitionOverlayTest : FunSpec({

    class CapturingGenerator(override val dialect: DatabaseDialect) : DdlGenerator {
        var seen: SchemaDefinition? = null
        override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
            seen = schema
            return DdlResult(statements = listOf(DdlStatement("CREATE TABLE sales (region_id INT);")))
        }

        override fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult =
            DdlResult(statements = emptyList())
    }

    fun listSchema() = SchemaDefinition(
        name = "shop",
        version = "1.0.0",
        tables = mapOf(
            "sales" to TableDefinition(
                columns = mapOf("region_id" to ColumnDefinition(type = NeutralType.Integer)),
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

    fun overlay(
        fingerprint: String,
        west: Pair<List<String>, String> = listOf("1", "2") to "3",
        east: Pair<List<String>, String> = listOf("5", "6") to "7",
    ) = MigrationOverlayDocument(
        source = "overlays/regions.json",
        overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.PARTITION_MAPPING,
            binding = MigrationOverlayBinding.Representation(fingerprint),
            dialect = "mssql",
            entries = listOf(
                PartitionMappingOverlayEntry(
                    id = "w", table = "sales", sourcePartition = "p_west",
                    values = west.first, rangeUpperBound = west.second,
                ),
                PartitionMappingOverlayEntry(
                    id = "e", table = "sales", sourcePartition = "p_east",
                    values = east.first, rangeUpperBound = east.second,
                ),
            ),
            createdAt = "2026-09-08T10:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash(),
    )

    class Harness(dialect: DatabaseDialect, val schema: SchemaDefinition) {
        val generator = CapturingGenerator(dialect)
        val errors = mutableListOf<String>()
        var notes: List<String> = emptyList()
        val runner = SchemaGenerateRunner(
            schemaReader = { schema },
            validator = { ValidationResult() },
            generatorLookup = { generator },
            reportWriter = { _, result, _, _, _, _, _ ->
                notes = result.notes.map { "${it.code}:${it.message} ${it.hint.orEmpty()}" }
            },
            fileWriter = { _, _ -> },
            formatJsonOutput = SchemaGenerateHelpers::formatJsonOutput,
            sidecarPath = SchemaGenerateHelpers::sidecarPath,
            rollbackPath = SchemaGenerateHelpers::rollbackPath,
            splitPath = SchemaGenerateHelpers::splitPath,
            printError = { msg, _ -> errors += msg },
            printValidationResult = { _, _, _ -> },
        )
    }

    fun run(
        dialect: DatabaseDialect,
        schema: SchemaDefinition,
        overlays: List<MigrationOverlayDocument>,
    ): Triple<Int, Harness, SchemaDefinition?> {
        val harness = Harness(dialect, schema)
        val exit = harness.runner.execute(
            SchemaGenerateRequest(
                source = Path.of("/tmp/schema.yaml"),
                target = dialect.name.lowercase(),
                migrationOverlays = overlays,
                output = null,
                report = Path.of("/tmp/report.json"),
                generateRollback = false,
                outputFormat = "plain",
                verbose = false,
                quiet = true,
            ),
        )
        return Triple(exit, harness, harness.generator.seen)
    }

    test("with a mapping, SQL Server gets RANGE — and is told that RANGE takes what LIST refused") {
        val schema = listSchema()
        val fingerprint = PartitionOverlayHint.representationFingerprint(schema, DatabaseDialect.MSSQL)
        val (exit, harness, seen) = run(DatabaseDialect.MSSQL, schema, listOf(overlay(fingerprint)))

        exit shouldBe 0
        val partitioning = seen.shouldNotBeNull().tables.getValue("sales").partitioning.shouldNotBeNull()
        partitioning.type shouldBe PartitionType.RANGE
        // Die Auffang-Partition oberhalb der letzten Grenze gehoert zum
        // Ergebnis, nicht zu den Nebenwirkungen.
        partitioning.partitions.map { it.name } shouldBe listOf("p_west", "p_east", "p_east_above")

        withClue(harness.notes.toString()) {
            harness.notes.single { it.startsWith(PartitionOverlayHint.LIST_RENDERED_AS_RANGE) }
                .shouldContain("p_east_above")
        }
    }

    test("without a mapping, the E055 case says what would resolve it — with the fingerprint to bind to") {
        val schema = listSchema()
        val (exit, harness, seen) = run(DatabaseDialect.MSSQL, schema, emptyList())

        exit shouldBe 0
        // Ohne Zuordnung wird nichts umgeschrieben; der Generator sieht LIST
        // und meldet E055 an seiner Stelle.
        seen.shouldNotBeNull().tables.getValue("sales").partitioning!!.type shouldBe PartitionType.LIST

        val hint = harness.notes.single { it.startsWith(PartitionOverlayHint.LIST_MAPPING_AVAILABLE) }
        hint shouldContain PartitionOverlayHint.representationFingerprint(schema, DatabaseDialect.MSSQL)
        hint shouldContain "rangeUpperBound"
    }

    test("a mapping that would route rows wrongly stops the run instead of falling back") {
        val schema = listSchema()
        val fingerprint = PartitionOverlayHint.representationFingerprint(schema, DatabaseDialect.MSSQL)
        // Die Grenze der ersten Menge reicht in die zweite hinein: '5' laege
        // damit in der ersten Partition.
        val (exit, harness, seen) = run(
            DatabaseDialect.MSSQL, schema,
            listOf(overlay(fingerprint, west = listOf("1", "2") to "9")),
        )

        exit shouldBe 2
        seen shouldBe null
        withClue(harness.errors.toString()) {
            harness.errors.joinToString() shouldContain "reaches into"
        }
    }

    test("a mapping that leaves a partition of the schema unnamed is refused") {
        // Das sieht nur, wer das Schema kennt: das Dokument fuer sich ist
        // widerspruchsfrei, es sagt bloss nichts ueber 'p_east'. Sie
        // stillschweigend fallenzulassen gaebe ihre Zeilen einer fremden
        // Partition.
        val schema = listSchema()
        val fingerprint = PartitionOverlayHint.representationFingerprint(schema, DatabaseDialect.MSSQL)
        val partial = MigrationOverlayDocument(
            source = "overlays/partial.json",
            overlay = overlay(fingerprint).overlay
                .copy(entries = overlay(fingerprint).overlay.entries.take(1))
                .withComputedHash(),
        )

        val (exit, harness, seen) = run(DatabaseDialect.MSSQL, schema, listOf(partial))

        exit shouldBe 2
        seen shouldBe null
        withClue(harness.errors.toString()) {
            harness.errors.joinToString() shouldContain "p_east"
        }
    }

    test("a mapping bound to another schema is refused before it can set anything") {
        val (exit, harness, seen) = run(DatabaseDialect.MSSQL, listSchema(), listOf(overlay("0000")))

        exit shouldBe 2
        seen shouldBe null
        harness.errors.joinToString() shouldContain "OVERLAY_STALE_SCHEMA_FINGERPRINT"
    }

    test("a dialect that knows LIST keeps it — translating there would lose the form, not gain one") {
        val schema = listSchema()
        val fingerprint = PartitionOverlayHint.representationFingerprint(schema, DatabaseDialect.POSTGRESQL)
        val pgOverlay = MigrationOverlayDocument(
            source = overlay(fingerprint).source,
            overlay = overlay(fingerprint).overlay.copy(
                dialect = "postgresql",
                binding = MigrationOverlayBinding.Representation(fingerprint),
            ).withComputedHash(),
        )
        val (exit, harness, seen) = run(DatabaseDialect.POSTGRESQL, schema, listOf(pgOverlay))

        exit shouldBe 0
        seen.shouldNotBeNull().tables.getValue("sales").partitioning!!.type shouldBe PartitionType.LIST
        harness.notes.none { it.startsWith(PartitionOverlayHint.LIST_RENDERED_AS_RANGE) } shouldBe true
    }
})
