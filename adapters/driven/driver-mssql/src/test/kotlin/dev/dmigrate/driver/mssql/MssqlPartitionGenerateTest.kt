package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.col
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.schema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Sub-Slice 7b: SQL Server beschreibt Partitionierung in zwei eigenständigen
 * Objekten, an die sich die Tabelle hängt. Beide müssen **vor** der Tabelle
 * stehen, und `RANGE RIGHT` ist nicht gewählt, sondern erzwungen — das neutrale
 * Modell beschreibt `[from, to)`.
 */
class MssqlPartitionGenerateTest : FunSpec({

    val generator = MssqlDdlGenerator()

    fun rangeOn(column: String, vararg bounds: String) = PartitionConfig(
        type = PartitionType.RANGE,
        key = listOf(column),
        partitions = bounds.toList().mapIndexed { i, b ->
            PartitionDefinition(
                name = "p${i + 1}",
                from = listOf(if (i == 0) PartitionBound.MinValue else PartitionBound.Value(bounds[i - 1])),
                to = listOf(PartitionBound.Value(b)),
            )
        } + PartitionDefinition(
            name = "p${bounds.size + 1}",
            from = listOf(PartitionBound.Value(bounds.last())),
            to = listOf(PartitionBound.MaxValue),
        ),
    )

    fun table(partitioning: PartitionConfig?, keyType: NeutralType = NeutralType.Integer) = TableDefinition(
        columns = linkedMapOf(
            "id" to col(NeutralType.Integer),
            "bucket" to col(keyType),
        ),
        partitioning = partitioning,
    )

    fun render(partitioning: PartitionConfig?, keyType: NeutralType = NeutralType.Integer, storage: String = "PRIMARY") =
        generator.generate(
            schema(tables = mapOf("events" to table(partitioning, keyType))),
            DdlGenerationOptions(partitionStorage = storage),
        )

    test("function and scheme are created before the table that hangs on them") {
        val statements = render(rangeOn("bucket", "100", "200")).statements.map { it.sql }
        val functionAt = statements.indexOfFirst { it.contains("CREATE PARTITION FUNCTION") }
        val schemeAt = statements.indexOfFirst { it.contains("CREATE PARTITION SCHEME") }
        val tableAt = statements.indexOfFirst { it.startsWith("CREATE TABLE") }

        (functionAt >= 0) shouldBe true
        // Das Scheme referenziert die Function, die Tabelle das Scheme.
        (schemeAt > functionAt) shouldBe true
        (tableAt > schemeAt) shouldBe true
    }

    test("n partitions become n-1 boundaries, and the table carries the ON clause") {
        val ddl = render(rangeOn("bucket", "100", "200")).render()
        // Drei Partitionen, zwei Schnittpunkte — MAXVALUE ist keine Grenze.
        ddl shouldContain "CREATE PARTITION FUNCTION [pf_events] (INT) AS RANGE RIGHT FOR VALUES (100, 200);"
        ddl shouldContain "CREATE PARTITION SCHEME [ps_events] AS PARTITION [pf_events] ALL TO ([PRIMARY]);"
        ddl shouldContain ") ON [ps_events] ([bucket]);"
    }

    test("the storage location comes from the generation options, not the model") {
        val ddl = render(rangeOn("bucket", "100"), storage = "fg_archive").render()
        ddl shouldContain "ALL TO ([fg_archive]);"
    }

    test("a string boundary gets the N prefix") {
        // Das Modell traegt Literale in PostgreSQL-Form. Ohne `N` vergliche
        // SQL Server sie in der Codepage der Datenbank statt in Unicode.
        val ddl = render(rangeOn("bucket", "'m'"), keyType = NeutralType.Text(10)).render()
        ddl shouldContain "FOR VALUES (N'm');"
    }

    test("the per-table duplication of shared objects is reported as W144") {
        val result = render(rangeOn("bucket", "100"))
        val note = result.notes.single { it.code == "W144" }
        note.message shouldContain "pf_events"
        note.message shouldContain "shares these objects"
    }

    test("LIST and HASH stay unrendered with E055") {
        for (strategy in listOf(PartitionType.LIST, PartitionType.HASH)) {
            val config = PartitionConfig(
                type = strategy, key = listOf("bucket"),
                partitions = listOf(PartitionDefinition(name = "p1", values = listOf("1"))),
            )
            val result = render(config)
            result.render() shouldNotContain "CREATE PARTITION FUNCTION"
            result.notes.any { it.code == "E055" } shouldBe true
        }
    }

    test("a partitioning without children is not rendered — the bounds are unknown") {
        // Das ist der RANGE-LEFT-Fall aus 7a: die Tatsache der Partitionierung
        // ist bekannt, die Grenzen nicht. Ein Scheme ohne Grenzen waere geraten.
        val result = render(PartitionConfig(type = PartitionType.RANGE, key = listOf("bucket")))
        result.render() shouldNotContain "CREATE PARTITION FUNCTION"
        result.notes.any { it.code == "E055" } shouldBe true
    }
})
