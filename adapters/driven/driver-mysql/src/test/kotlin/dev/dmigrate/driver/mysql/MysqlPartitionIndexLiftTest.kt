package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.NoteType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * AP6.3 / ADR 0020 §5 — PG-kind-lokale Partition-Indizes auf MySQL-Tabellenebene heben:
 * nicht-unique heben (dedupliziert, Kollision → W131), UNIQUE skip + E064.
 */
class MysqlPartitionIndexLiftTest : FunSpec({

    val generator = MysqlDdlGenerator()

    fun col(type: NeutralType, required: Boolean = false) =
        ColumnDefinition(type = type, required = required)

    fun schemaOf(table: TableDefinition) = SchemaDefinition(
        name = "test_schema", version = "1.0", tables = mapOf("events" to table),
    )

    fun rangeTable(
        tableIndices: List<IndexDefinition> = emptyList(),
        partitionIndices: List<IndexDefinition> = emptyList(),
    ) = TableDefinition(
        columns = mapOf(
            "id" to col(NeutralType.Identifier(autoIncrement = true)),
            "event_date" to col(NeutralType.Date, required = true),
            "payload" to col(NeutralType.Text(100)),
        ),
        primaryKey = listOf("id", "event_date"),
        indices = tableIndices,
        partitioning = PartitionConfig(
            type = PartitionType.RANGE,
            key = listOf("event_date"),
            partitions = listOf(
                PartitionDefinition(
                    name = "p2024", to = listOf(PartitionBound.Value("'2025-01-01'")), indices = partitionIndices,
                ),
                PartitionDefinition(
                    name = "p2025", to = listOf(PartitionBound.MaxValue), indices = partitionIndices,
                ),
            ),
        ),
    )

    test("non-unique child-local index is lifted once to the table (dedup across partitions) + INFO note") {
        val idx = IndexDefinition(name = "events_payload_idx", columns = listOf(IndexColumn("payload")))
        // The same index sits on BOTH partitions; lifting must collapse it to a single table index.
        val result = generator.generate(schemaOf(rangeTable(partitionIndices = listOf(idx))))
        val ddl = result.render()

        ddl shouldContain "CREATE INDEX `events_payload_idx` ON `events` (`payload`);"
        Regex("CREATE INDEX `events_payload_idx`").findAll(ddl).count() shouldBe 1
        result.notes.find { it.code == "PARTITION_INDEX_LIFTED" }!!.type shouldBe NoteType.INFO
    }

    test("UNIQUE child-local index is NOT lifted — skip + E064 (ADR 0020 §5)") {
        val uniqueIdx = IndexDefinition(
            name = "events_uq", columns = listOf(IndexColumn("payload")), unique = true,
        )
        val result = generator.generate(schemaOf(rangeTable(partitionIndices = listOf(uniqueIdx))))
        val ddl = result.render()

        result.notes.find { it.code == "E064" }!!.type shouldBe NoteType.ACTION_REQUIRED
        // The note text mentions 'events_uq' (single quotes); no CREATE INDEX statement (backticks) must.
        ddl shouldNotContain "INDEX `events_uq`"
    }

    test("partition-local partial indices differing only in WHERE are not collapsed (AP6-review #8)") {
        // Same columns/type/unique, different predicate → distinct indices. The lift signature must
        // include `where`, else they collapse to one (only one survives the dedup). Both are partial
        // → both get an E057 skip note; without the fix only one name would appear.
        val a = IndexDefinition(
            name = "idx_a", columns = listOf(IndexColumn("payload")), where = "payload IS NOT NULL",
        )
        val b = IndexDefinition(
            name = "idx_b", columns = listOf(IndexColumn("payload")), where = "payload = ''",
        )
        val result = generator.generate(schemaOf(rangeTable(partitionIndices = listOf(a, b))))
        result.notes.filter { it.code == "E057" }.map { it.objectName }.toSet() shouldBe setOf("idx_a", "idx_b")
    }

    test("lifted index whose name collides with a table index is renamed + W131") {
        val tableIdx = IndexDefinition(name = "dup_idx", columns = listOf(IndexColumn("id")))
        // Different signature (different column) but the same name → collision after lifting.
        val partIdx = IndexDefinition(name = "dup_idx", columns = listOf(IndexColumn("payload")))
        val result = generator.generate(
            schemaOf(rangeTable(tableIndices = listOf(tableIdx), partitionIndices = listOf(partIdx))),
        )
        val ddl = result.render()

        result.notes.find { it.code == "W131" }!!.type shouldBe NoteType.WARNING
        ddl shouldContain "CREATE INDEX `dup_idx` ON `events` (`id`);"
        ddl shouldContain "CREATE INDEX `dup_idx_2` ON `events` (`payload`);"
    }
})
