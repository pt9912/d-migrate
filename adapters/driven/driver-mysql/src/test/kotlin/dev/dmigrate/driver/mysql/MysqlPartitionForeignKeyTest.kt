package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.NoteType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * AP6.3-FK / ADR 0020 §5 — MySQL/InnoDB supports no foreign keys on partitioned tables (either
 * direction). FKs on/referencing a partitioned table are skipped + E065 across all three emission
 * paths (inline column ref, explicit constraint, circular ALTER); a *skipped* partition config
 * (E055/E062) leaves a plain table whose FKs stay valid.
 */
class MysqlPartitionForeignKeyTest : FunSpec({

    val generator = MysqlDdlGenerator()

    fun col(type: NeutralType, required: Boolean = false, references: ReferenceDefinition? = null) =
        ColumnDefinition(type = type, required = required, references = references)

    fun customers() = TableDefinition(
        columns = mapOf("id" to col(NeutralType.Identifier(autoIncrement = true))),
        primaryKey = listOf("id"),
    )

    fun rangePartition(partitions: List<PartitionDefinition>) = PartitionConfig(
        type = PartitionType.RANGE, key = listOf("event_date"), partitions = partitions,
    )

    val twoPartitions = listOf(
        PartitionDefinition(name = "p2024", to = listOf(PartitionBound.Value("'2025-01-01'"))),
        PartitionDefinition(name = "p_max", to = listOf(PartitionBound.MaxValue)),
    )

    fun schemaOf(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "test_schema", version = "1.0", tables = mapOf(*tables))

    test("explicit FOREIGN_KEY constraint on a partitioned table is skipped + E065") {
        val events = TableDefinition(
            columns = mapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "event_date" to col(NeutralType.Date, required = true),
                "customer_id" to col(NeutralType.Integer, required = true),
            ),
            primaryKey = listOf("id", "event_date"),
            constraints = listOf(
                ConstraintDefinition(
                    name = "fk_events_customer", type = ConstraintType.FOREIGN_KEY,
                    columns = listOf("customer_id"),
                    references = ConstraintReferenceDefinition(table = "customers", columns = listOf("id")),
                ),
                ConstraintDefinition(
                    name = "chk_id", type = ConstraintType.CHECK, expression = "id > 0",
                ),
            ),
            partitioning = rangePartition(twoPartitions),
        )
        val result = generator.generate(schemaOf("customers" to customers(), "events" to events))
        val ddl = result.render()

        result.notes.find { it.code == "E065" }!!.type shouldBe NoteType.ACTION_REQUIRED
        ddl shouldNotContain "FOREIGN KEY"
        ddl shouldContain "PARTITION BY RANGE COLUMNS (`event_date`)"
        ddl shouldContain "CHECK (id > 0)" // non-FK constraints survive
    }

    test("inline column-reference FK on a partitioned table is skipped + E065") {
        val events = TableDefinition(
            columns = mapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "event_date" to col(NeutralType.Date, required = true),
                "customer_id" to col(
                    NeutralType.Integer, references = ReferenceDefinition(table = "customers", column = "id"),
                ),
            ),
            primaryKey = listOf("id", "event_date"),
            partitioning = rangePartition(twoPartitions),
        )
        val result = generator.generate(schemaOf("customers" to customers(), "events" to events))
        val ddl = result.render()

        result.notes.any { it.code == "E065" } shouldBe true
        ddl shouldNotContain "FOREIGN KEY"
    }

    test("FK referencing a partitioned table (reverse direction) is skipped + E065") {
        val parent = TableDefinition(
            columns = mapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "event_date" to col(NeutralType.Date, required = true),
            ),
            primaryKey = listOf("id", "event_date"),
            partitioning = rangePartition(twoPartitions),
        )
        // child is NOT partitioned but points at the partitioned parent — MySQL forbids this too.
        val child = TableDefinition(
            columns = mapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "parent_id" to col(
                    NeutralType.Integer, references = ReferenceDefinition(table = "parent", column = "id"),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val result = generator.generate(schemaOf("parent" to parent, "child" to child))
        val ddl = result.render()

        result.notes.any { it.code == "E065" } shouldBe true
        ddl shouldNotContain "FOREIGN KEY"
    }

    test("circular FK touching a partitioned table is skipped + E065 (no ALTER ADD CONSTRAINT)") {
        val tableA = TableDefinition(
            columns = mapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "event_date" to col(NeutralType.Date, required = true),
                "b_id" to col(NeutralType.Integer, references = ReferenceDefinition(table = "table_b", column = "id")),
            ),
            primaryKey = listOf("id", "event_date"),
            partitioning = PartitionConfig(type = PartitionType.RANGE, key = listOf("event_date"), partitions = twoPartitions),
        )
        val tableB = TableDefinition(
            columns = mapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "a_id" to col(NeutralType.Integer, references = ReferenceDefinition(table = "table_a", column = "id")),
            ),
            primaryKey = listOf("id"),
        )
        val result = generator.generate(schemaOf("table_a" to tableA, "table_b" to tableB))
        val ddl = result.render()

        result.notes.any { it.code == "E065" } shouldBe true
        ddl shouldNotContain "ADD CONSTRAINT"
        ddl shouldNotContain "FOREIGN KEY"
    }

    test("FK is KEPT when the partition config is SKIPPED (E055) — table is not actually partitioned") {
        val events = TableDefinition(
            columns = mapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "event_date" to col(NeutralType.Date, required = true),
                "customer_id" to col(
                    NeutralType.Integer, references = ReferenceDefinition(table = "customers", column = "id"),
                ),
            ),
            primaryKey = listOf("id"),
            // Empty RANGE partitions → E055, no partition clause → events stays a plain table.
            partitioning = PartitionConfig(type = PartitionType.RANGE, key = listOf("event_date"), partitions = emptyList()),
        )
        val result = generator.generate(schemaOf("customers" to customers(), "events" to events))
        val ddl = result.render()

        result.notes.any { it.code == "E055" } shouldBe true
        result.notes.any { it.code == "E065" } shouldBe false
        ddl shouldContain "FOREIGN KEY (`customer_id`) REFERENCES `customers`"
    }
})
