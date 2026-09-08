package dev.dmigrate.driver.mssql

import dev.dmigrate.cli.commands.PartitionNameOverlayApplier
import dev.dmigrate.cli.commands.PartitionOverlayHint
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayBinding
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidationContext
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidator
import dev.dmigrate.core.diff.migration.overlay.PartitionMappingOverlayEntry
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Partitionsnamen aus einem Overlay — gegen ein echtes SQL Server.
 *
 * Was kein Unit-Test zeigen kann: unter welchen Bezeichnern der Server die
 * Partitionen wirklich zurueckgibt. `sys.partitions` fuehrt Nummern, der
 * Reverse synthetisiert daraus `p1`, `p2`, … in Grenzreihenfolge — dass es
 * genau diese sind und in dieser Reihenfolge, sagt nur der Server.
 *
 * Der Test schliesst zugleich die Kette aus P4: der Fingerabdruck wird **aus
 * der Meldung gelesen**, nicht aus derselben Funktion geholt, und das damit
 * gebaute Overlay muss der Validator gegen das gelesene Schema annehmen.
 */
class MssqlPartitionNameOverlayIntegrationTest : FunSpec({

    val container = startMssqlContainer()
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = poolFor(container, "dmigrate_partition_names")
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun fingerprintFrom(message: String): String? =
        Regex("schemaFingerprint `([0-9a-f]+)`").find(message)?.groupValues?.get(1)

    test("the reverse numbers the partitions, and the overlay gives them their names back") {
        execDdl(
            pool,
            "IF OBJECT_ID('events') IS NOT NULL DROP TABLE events",
            "CREATE PARTITION FUNCTION pf_events (INT) AS RANGE RIGHT FOR VALUES (100, 200)",
            "CREATE PARTITION SCHEME ps_events AS PARTITION pf_events ALL TO ([PRIMARY])",
            "CREATE TABLE events (id INT NOT NULL, payload NVARCHAR(50) NULL) ON ps_events(id)",
        )
        try {
            val read = MssqlSchemaReader().read(pool)
            val partitions = read.schema.tables.getValue("events").partitioning
            withClue("die Tabelle kam nicht partitioniert zurueck") { partitions.shouldNotBeNull() }

            // Der gemessene Ist-Zustand: nummerierte Namen, und die Meldung
            // dazu.
            partitions!!.partitions.map { it.name } shouldContainExactly listOf("p1", "p2", "p3")
            val note = read.notes.single { it.code == "R346" }

            // P4: der Hinweis nennt den Abdruck, an den zu binden ist.
            val enriched = PartitionOverlayHint
                .enrichReadNotes(read.notes, read.schema, DatabaseDialect.MSSQL)
                .single { it.code == "R346" }
            val fingerprint = fingerprintFrom(enriched.hint.orEmpty())
            withClue(enriched.hint.orEmpty()) {
                fingerprint.shouldNotBeNull()
                enriched.hint!! shouldContain MigrationOverlayKinds.PARTITION_MAPPING
            }

            val overlay = MigrationOverlay(
                overlayKind = MigrationOverlayKinds.PARTITION_MAPPING,
                binding = MigrationOverlayBinding.Representation(fingerprint!!),
                dialect = "mssql",
                entries = listOf("p1" to "p_before_100", "p2" to "p_100_200", "p3" to "p_after_200")
                    .mapIndexed { index, (target, source) ->
                        PartitionMappingOverlayEntry(
                            id = "e$index", table = "events",
                            sourcePartition = source, targetPartition = target,
                        )
                    },
                createdAt = "2026-09-08T10:00:00Z",
                createdByVersion = "d-migrate-test",
            ).withComputedHash()

            // Der Validator nimmt es an — gegen das Schema, das der Server
            // gerade geliefert hat.
            val validation = MigrationOverlayValidator.validate(
                overlay = overlay,
                context = MigrationOverlayValidationContext(
                    expectedSourceFingerprint = "unused",
                    expectedTargetFingerprint = "unused",
                    expectedDialect = "mssql",
                    expectedRepresentationFingerprint =
                        PartitionOverlayHint.representationFingerprint(read.schema, DatabaseDialect.MSSQL),
                ),
                source = "overlays/events.json",
            )
            withClue(validation.diagnostics.map { it.code to it.message }.toString()) {
                validation.hasBlockers shouldBe false
            }

            // Und die Namen stehen im Ergebnis, die Meldung verstummt.
            val applied = PartitionNameOverlayApplier.apply(
                read.schema, read.notes,
                listOf(MigrationOverlayDocument("overlays/events.json", overlay)),
            )
            applied.schema.tables.getValue("events").partitioning!!.partitions.map { it.name } shouldContainExactly
                listOf("p_before_100", "p_100_200", "p_after_200")
            applied.notes.none { it.code == "R346" } shouldBe true

            // Gegenprobe: ohne Overlay bleibt beides, wie es war.
            note.message shouldContain "numbers partitions"
        } finally {
            runCatching { execDdl(pool, "IF OBJECT_ID('events') IS NOT NULL DROP TABLE events") }
            runCatching { execDdl(pool, "DROP PARTITION SCHEME ps_events") }
            runCatching { execDdl(pool, "DROP PARTITION FUNCTION pf_events") }
        }
    }
})
