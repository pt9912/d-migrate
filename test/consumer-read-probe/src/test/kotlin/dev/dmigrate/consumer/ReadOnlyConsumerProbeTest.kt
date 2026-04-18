package dev.dmigrate.consumer

import dev.dmigrate.core.dependency.FkEdge
import dev.dmigrate.core.dependency.sortTablesByDependency
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.format.data.DataChunkReaderFactory
import dev.dmigrate.format.data.FormatReadOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Consumer integration probe: verifies that a read-only external
 * consumer (e.g. source-d-migrate for d-browser) can use d-migrate's
 * read-oriented types without importing write-, CLI-, or profiling
 * modules.
 *
 * This test compiles and runs WITHOUT any of:
 * - dev.dmigrate.driver.data.ImportOptions (write-oriented)
 * - dev.dmigrate.driver.data.DataWriter (write-oriented)
 * - dev.dmigrate.driver.data.TableImportSession (write-oriented)
 * - dev.dmigrate.profiling.* (profiling)
 * - dev.dmigrate.streaming.* (streaming orchestration)
 * - dev.dmigrate.cli.* (CLI wiring)
 *
 * If this test compiles, the read integration surface is clean.
 */
class ReadOnlyConsumerProbeTest : FunSpec({

    test("SchemaDefinition can be constructed and projected") {
        val schema = SchemaDefinition(
            name = "probe-schema",
            version = "1.0",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Identifier()),
                        "name" to ColumnDefinition(type = NeutralType.Text()),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        schema.tables.size shouldBe 1
        schema.tables["users"]!!.primaryKey shouldBe listOf("id")
    }

    test("SchemaReadResult can be projected to consumer domain") {
        val readResult = SchemaReadResult(
            schema = SchemaDefinition(name = "test", version = "1"),
        )

        // Consumer projects to its own types
        val snapshot = ConsumerSchemaSnapshot(
            name = readResult.schema.name,
            tableCount = readResult.schema.tables.size,
            hasNotes = readResult.notes.isNotEmpty(),
        )

        snapshot.name shouldBe "test"
        snapshot.tableCount shouldBe 0
        snapshot.hasNotes shouldBe false
    }

    test("FormatReadOptions is accessible without ImportOptions") {
        val opts = FormatReadOptions(csvNoHeader = true, csvNullString = "NULL")
        opts.csvNoHeader shouldBe true
        opts.csvNullString shouldBe "NULL"
    }

    test("FK topo-sort utility is reusable from core") {
        val result = sortTablesByDependency(
            setOf("orders", "users"),
            listOf(FkEdge("orders", "user_id", "users", "id")),
        )
        result.sorted shouldBe listOf("users", "orders")
        result.circularEdges.size shouldBe 0
    }

    test("DataChunkReaderFactory interface is accessible") {
        // Compile check: the interface is visible from the read surface
        val factoryType: Class<DataChunkReaderFactory> = DataChunkReaderFactory::class.java
        factoryType shouldNotBe null
    }
})

/** Example consumer-side projection type. */
private data class ConsumerSchemaSnapshot(
    val name: String,
    val tableCount: Int,
    val hasNotes: Boolean,
)
