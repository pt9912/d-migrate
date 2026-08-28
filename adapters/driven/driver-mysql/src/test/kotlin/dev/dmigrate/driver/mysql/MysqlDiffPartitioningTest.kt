package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Gegenstueck zu `PostgresDiffPartitioningTest`: der Migrationspfad legte eine
 * partitionierte Tabelle flach an. Bei MySQL kommt die FK-Regel dazu (ADR 0020)
 * — Fremdschluessel und Partitionierung schliessen einander aus, und der
 * Generate-Pfad wusste das laengst.
 */
class MysqlDiffPartitioningTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()

    fun renderUp(t: TableDefinition): MigrationDdlResult {
        val empty = SchemaDefinition(name = "App", version = "1")
        val desired = empty.copy(tables = mapOf("events" to t))
        val planned = planner.plan(empty, desired, SchemaDiff(tablesAdded = listOf(NamedTable("events", t))))
        return gen.generateUp(planned, DdlGenerationOptions())
    }

    fun table(partitioning: PartitionConfig?, withFk: Boolean = false) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(type = NeutralType.Integer),
            "bucket" to ColumnDefinition(type = NeutralType.Integer),
            "owner_id" to ColumnDefinition(
                type = NeutralType.Integer,
                references = if (withFk) ReferenceDefinition(table = "owners", column = "id") else null,
            ),
        ),
        partitioning = partitioning,
    )

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

        sql shouldContain "PARTITION BY RANGE COLUMNS"
        sql shouldContain "PARTITION `p_low`"
        sql shouldContain "PARTITION `p_high`"
        result.blockers.shouldBeEmpty()
    }

    test("an unpartitioned table is unaffected") {
        renderUp(table(null)).statements.joinToString("\n") { it.sql } shouldNotContain "PARTITION"
    }

    // ADR 0020: MySQL weist eine partitionierte Tabelle MIT Fremdschluessel ab.
    // Ohne diese Behandlung erzeugte der Diff DDL, die der Server zurueckweist.
    test("a foreign key is dropped on a partitioned table, with a warning") {
        val result = renderUp(table(ranged, withFk = true))
        val sql = result.statements.joinToString("\n") { it.sql }

        sql shouldNotContain "REFERENCES"
        (result.diagnostics.map { it.code }).contains("E065") shouldBe true
    }

    test("the same foreign key survives when the table is not partitioned") {
        renderUp(table(null, withFk = true)).statements.joinToString("\n") { it.sql } shouldContain "REFERENCES"
    }

    test("partitioning MySQL cannot express blocks instead of silently flattening") {
        val childless = PartitionConfig(type = PartitionType.RANGE, key = listOf("bucket"))
        val result = renderUp(table(childless))

        result.statements.shouldBeEmpty()
        result.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }
})
