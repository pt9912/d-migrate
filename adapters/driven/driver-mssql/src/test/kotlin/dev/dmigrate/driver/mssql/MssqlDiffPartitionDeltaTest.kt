package dev.dmigrate.driver.mssql

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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Eine geaenderte Partitionsgrenze wird ausgefuehrt statt gemeldet.
 *
 * SQL Server hat keine Kind-Anweisung: Partitionen sind die Abschnitte
 * zwischen den Grenzwerten der Partition Function. Das Kind-Delta wird
 * deshalb in ein Grenz-Delta zurueckgerechnet — und genau deshalb darf der
 * Renderer dem Kind-Delta nicht woertlich folgen: eine eingefuegte Grenze
 * erscheint dort als ein entferntes und zwei hinzugekommene Kinder.
 */
class MssqlDiffPartitionDeltaTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    fun child(name: String, from: PartitionBound, to: PartitionBound) =
        PartitionDefinition(name = name, from = listOf(from), to = listOf(to))

    fun config(vararg children: PartitionDefinition) =
        PartitionConfig(PartitionType.RANGE, listOf("bucket"), children.toList())

    fun table(partitioning: PartitionConfig) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Integer),
            "bucket" to ColumnDefinition(NeutralType.Integer),
        ),
        partitioning = partitioning,
    )

    fun schema(partitioning: PartitionConfig) =
        SchemaDefinition(name = "App", version = "1", tables = mapOf("events" to table(partitioning)))

    fun renderUp(before: PartitionConfig, after: PartitionConfig) = gen.generateUp(
        planner.plan(
            schema(before),
            schema(after),
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "events", partitioning = ValueChange(before, after)))),
        ),
        DdlGenerationOptions(),
    )

    val twoWay = config(
        child("p1", PartitionBound.MinValue, PartitionBound.Value("100")),
        child("p2", PartitionBound.Value("100"), PartitionBound.MaxValue),
    )
    val threeWay = config(
        child("p1", PartitionBound.MinValue, PartitionBound.Value("100")),
        child("p2", PartitionBound.Value("100"), PartitionBound.Value("200")),
        child("p3", PartitionBound.Value("200"), PartitionBound.MaxValue),
    )

    test("eine eingefuegte Grenze ist ein SPLIT, kein Neubau") {
        val sqls = renderUp(twoWay, threeWay).statements.map { it.sql }

        sqls shouldBe listOf(
            "ALTER PARTITION SCHEME [ps_events] NEXT USED [PRIMARY];",
            "ALTER PARTITION FUNCTION [pf_events]() SPLIT RANGE (200);",
        )
    }

    test("eine entfallene Grenze ist ein MERGE") {
        val sqls = renderUp(threeWay, twoWay).statements.map { it.sql }

        sqls shouldBe listOf("ALTER PARTITION FUNCTION [pf_events]() MERGE RANGE (200);")
    }

    test("die Filegroup fuer NEXT USED kommt aus denselben Optionen wie das Generate") {
        val result = gen.generateUp(
            planner.plan(
                schema(twoWay),
                schema(threeWay),
                SchemaDiff(
                    tablesChanged = listOf(TableDiff(name = "events", partitioning = ValueChange(twoWay, threeWay))),
                ),
            ),
            DdlGenerationOptions(partitionStorage = "fg_archive"),
        )

        result.statements.first().sql shouldBe "ALTER PARTITION SCHEME [ps_events] NEXT USED [fg_archive];"
    }

    test("der Rueckbau dreht SPLIT und MERGE um") {
        val sqls = gen.generateDown(
            planner.plan(
                schema(twoWay),
                schema(threeWay),
                SchemaDiff(
                    tablesChanged = listOf(TableDiff(name = "events", partitioning = ValueChange(twoWay, threeWay))),
                ),
            ),
            DdlGenerationOptions(),
        ).statements.map { it.sql }

        sqls shouldBe listOf("ALTER PARTITION FUNCTION [pf_events]() MERGE RANGE (200);")
    }
})
