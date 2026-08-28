package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Der Migrationspfad legte eine partitionierte Tabelle FLACH an — ohne Blocker,
 * ohne Diagnose. Der Generate-Pfad konnte es laengst; nur dieser nicht. Diese
 * Spec haelt beide Haelften fest: dass partitioniert wird, und dass der eine
 * nicht renderbare Fall blockt statt still etwas Brauchbares zu hinterlassen.
 */
class PostgresDiffPartitioningTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun table(partitioning: PartitionConfig?) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(type = NeutralType.Integer),
            "bucket" to ColumnDefinition(type = NeutralType.Integer),
        ),
        partitioning = partitioning,
    )

    fun renderUp(t: TableDefinition): dev.dmigrate.driver.migration.MigrationDdlResult {
        val empty = SchemaDefinition(name = "App", version = "1")
        val desired = empty.copy(tables = mapOf("events" to t))
        val planned = planner.plan(empty, desired, SchemaDiff(tablesAdded = listOf(NamedTable("events", t))))
        return gen.generateUp(planned, DdlGenerationOptions())
    }

    val ranged = PartitionConfig(
        type = PartitionType.RANGE,
        key = listOf("bucket"),
        partitions = listOf(
            PartitionDefinition(
                name = "p_low",
                from = listOf(PartitionBound.MinValue),
                to = listOf(PartitionBound.Value("100")),
            ),
            PartitionDefinition(
                name = "p_high",
                from = listOf(PartitionBound.Value("100")),
                to = listOf(PartitionBound.MaxValue),
            ),
        ),
    )

    test("a migration creates the table partitioned, not flat") {
        val result = renderUp(table(ranged))
        val sql = result.statements.joinToString("\n") { it.sql }

        sql shouldContain "PARTITION BY RANGE (\"bucket\")"
        sql shouldContain "CREATE TABLE \"p_low\" PARTITION OF \"events\" FOR VALUES FROM (MINVALUE) TO (100);"
        sql shouldContain "CREATE TABLE \"p_high\" PARTITION OF \"events\" FOR VALUES FROM (100) TO (MAXVALUE);"
        result.blockers.shouldBeEmpty()
    }

    test("the partition clause sits outside the column parenthesis") {
        val create = renderUp(table(ranged)).statements.first().sql
        // Die Klausel steht hinter der schliessenden Klammer, nicht in der Spaltenliste.
        create shouldContain ") PARTITION BY RANGE"
    }

    test("an unpartitioned table is unaffected") {
        val sql = renderUp(table(null)).statements.joinToString("\n") { it.sql }
        sql shouldNotContain "PARTITION"
    }

    // PostgreSQL hat keine implizite Default-Partition: ein `PARTITION BY` ohne
    // Kinder nimmt keine Zeile an. Flach anlegen waere hier schlimmer als
    // blocken — die Tabelle saehe danach benutzbar aus.
    test("partitioning without children blocks instead of silently flattening") {
        val childless = PartitionConfig(type = PartitionType.RANGE, key = listOf("bucket"))
        val result = renderUp(table(childless))

        result.statements.shouldBeEmpty()
        result.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }
})
