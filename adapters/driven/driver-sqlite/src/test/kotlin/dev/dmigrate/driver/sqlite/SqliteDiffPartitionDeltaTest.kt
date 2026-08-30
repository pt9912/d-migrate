package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
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

/**
 * SQLite partitioniert nicht. Die Ablehnung nennt das als Grund — die
 * allgemeine „nicht in der ersten Matrix"-Meldung liesse offen, ob das ein
 * Schnitt ist oder eine Eigenschaft der Datenbank.
 */
class SqliteDiffPartitionDeltaTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    fun child(name: String, to: PartitionBound) =
        PartitionDefinition(name = name, from = listOf(PartitionBound.MinValue), to = listOf(to))

    fun config(vararg children: PartitionDefinition) =
        PartitionConfig(PartitionType.RANGE, listOf("bucket"), children.toList())

    fun schema(partitioning: PartitionConfig) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf(
            "events" to TableDefinition(
                columns = linkedMapOf("bucket" to ColumnDefinition(NeutralType.Integer)),
                partitioning = partitioning,
            ),
        ),
    )

    test("eine Partitionsaenderung wird mit SQLite als Grund abgelehnt") {
        val before = config(child("p100", PartitionBound.Value("100")))
        val after = config(child("p100", PartitionBound.Value("100")), child("p200", PartitionBound.Value("200")))

        val result = gen.generateUp(
            planner.plan(
                schema(before),
                schema(after),
                SchemaDiff(
                    tablesChanged = listOf(TableDiff(name = "events", partitioning = ValueChange(before, after))),
                ),
            ),
            DdlGenerationOptions(),
        )

        result.statements.shouldBeEmpty()
        result.diagnostics.single { it.code == "PARTITIONING_NOT_SUPPORTED_BY_DIALECT" }
            .message shouldContain "SQLite has no table partitioning"
        result.blockers.map { it.reason } shouldBe listOf(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION)
    }
})
