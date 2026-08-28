package dev.dmigrate.driver.mssql

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
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MssqlHashPartitionMode
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Sub-Slice 7c: der Migrationspfad rendert Partitionierung, statt sie zu
 * blocken. Zwei Dinge unterscheiden SQL Server von den anderen Dialekten und
 * stehen deshalb hier fest:
 *
 * - Function und Scheme sind **eigenstaendige Datenbankobjekte** und muessen
 *   vor der Tabelle stehen, die sich an sie haengt.
 * - `DROP TABLE` raeumt sie **nicht** mit weg. Ohne expliziten Rueckbau
 *   scheiterte der naechste Vorwaertslauf am schon vorhandenen Namen.
 */
class MssqlDiffPartitioningTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    fun schema(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables.toMap())

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

    fun table(partitioning: PartitionConfig?) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Integer),
            "bucket" to ColumnDefinition(NeutralType.Integer),
        ),
        partitioning = partitioning,
    )

    fun upFor(t: TableDefinition, options: DdlGenerationOptions = DdlGenerationOptions()) = gen.generateUp(
        planner.plan(schema(), schema("events" to t), SchemaDiff(tablesAdded = listOf(NamedTable("events", t)))),
        options,
    )

    fun downFor(t: TableDefinition) = gen.generateDown(
        planner.plan(schema(), schema("events" to t), SchemaDiff(tablesAdded = listOf(NamedTable("events", t)))),
        DdlGenerationOptions(),
    )

    test("function and scheme are created before the table that hangs on them") {
        val sqls = upFor(table(ranged)).statements.map { it.sql }
        val functionAt = sqls.indexOfFirst { it.contains("CREATE PARTITION FUNCTION") }
        val schemeAt = sqls.indexOfFirst { it.contains("CREATE PARTITION SCHEME") }
        val tableAt = sqls.indexOfFirst { it.startsWith("CREATE TABLE") }

        (functionAt >= 0) shouldBe true
        (schemeAt > functionAt) shouldBe true
        (tableAt > schemeAt) shouldBe true
    }

    test("boundaries and the ON clause match the generate path") {
        val ddl = upFor(table(ranged)).statements.joinToString("\n") { it.sql }

        ddl shouldContainStr "CREATE PARTITION FUNCTION [pf_events] (INT) AS RANGE RIGHT FOR VALUES (100);"
        ddl shouldContainStr "CREATE PARTITION SCHEME [ps_events] AS PARTITION [pf_events] ALL TO ([PRIMARY]);"
        ddl shouldContainStr ") ON [ps_events] ([bucket]);"
    }

    test("the storage location comes from the generation options") {
        val ddl = upFor(table(ranged), DdlGenerationOptions(partitionStorage = "fg_archive"))
            .statements.joinToString("\n") { it.sql }
        ddl shouldContainStr "ALL TO ([fg_archive]);"
    }

    test("W144 reports that function and scheme were created for this table alone") {
        upFor(table(ranged)).diagnostics.count { it.code == "W144" } shouldBe 1
    }

    // DROP TABLE laesst Function und Scheme stehen. Beim naechsten Vorwaertslauf
    // kollidierte der Name; die Reihenfolge ist erzwungen, weil das Scheme an
    // der Function haengt.
    test("the inverse drops scheme and function after the table") {
        val sqls = downFor(table(ranged)).statements.map { it.sql }

        sqls.indexOfFirst { it.startsWith("DROP TABLE") } shouldBe 0
        sqls[1] shouldContainStr "DROP PARTITION SCHEME [ps_events];"
        sqls[2] shouldContainStr "DROP PARTITION FUNCTION [pf_events];"
    }

    test("an unpartitioned table needs no teardown") {
        downFor(table(null)).statements.map { it.sql }.size shouldBe 1
    }

    // Was `generate` kann, muss `migrate` auch koennen — sonst haengt das
    // Ergebnis davon ab, auf welchem Weg die Tabelle entsteht.
    test("the hash emulation reaches the migration path too") {
        val hash = PartitionConfig(
            type = PartitionType.HASH,
            key = listOf("bucket"),
            partitions = (0 until 4).map {
                PartitionDefinition(name = "p$it", modulus = 4, remainder = it)
            },
        )
        val result = gen.generateUp(
            planner.plan(
                schema(),
                schema("events" to table(hash)),
                SchemaDiff(tablesAdded = listOf(NamedTable("events", table(hash)))),
            ),
            DdlGenerationOptions(
                dialectContext = DdlDialectContext.MsSql(
                    hashPartitionMode = MssqlHashPartitionMode.COMPUTED_COLUMN,
                ),
            ),
        )
        val sql = result.statements.joinToString("\n") { it.sql }

        sql shouldContainStr "[dmg_hash_bucket] AS (ABS(CHECKSUM([bucket]) % 4)) PERSISTED"
        sql shouldContainStr "AS RANGE RIGHT FOR VALUES (1, 2, 3);"
        sql shouldContainStr ") ON [ps_events] ([dmg_hash_bucket]);"
        result.diagnostics.count { it.code == "W145" } shouldBe 1
    }

    // D3: `DROP TABLE` laesst Function und Scheme stehen. Fuer den emulierten
    // HASH-Fall sagte `isRenderable` faelschlich "nichts angelegt", der Rueckbau
    // unterblieb, und der naechste Vorwaertslauf kollidierte am Namen.
    test("the inverse of an emulated hash table drops scheme and function too") {
        val hash = PartitionConfig(
            type = PartitionType.HASH,
            key = listOf("bucket"),
            partitions = (0 until 4).map { PartitionDefinition(name = "p$it", modulus = 4, remainder = it) },
        )
        val t = table(hash)
        val sqls = gen.generateDown(
            planner.plan(schema(), schema("events" to t), SchemaDiff(tablesAdded = listOf(NamedTable("events", t)))),
            DdlGenerationOptions(
                dialectContext = DdlDialectContext.MsSql(
                    hashPartitionMode = MssqlHashPartitionMode.COMPUTED_COLUMN,
                ),
            ),
        ).statements.map { it.sql }

        sqls.indexOfFirst { it.startsWith("DROP TABLE") } shouldBe 0
        sqls[1] shouldContainStr "DROP PARTITION SCHEME [ps_events];"
        sqls[2] shouldContainStr "DROP PARTITION FUNCTION [pf_events];"
    }

    test("without the mode the migration path still blocks a hash table") {
        val hash = PartitionConfig(
            type = PartitionType.HASH,
            key = listOf("bucket"),
            partitions = (0 until 4).map {
                PartitionDefinition(name = "p$it", modulus = 4, remainder = it)
            },
        )
        upFor(table(hash)).primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("LIST partitioning blocks — SQL Server knows only RANGE") {
        val list = PartitionConfig(
            type = PartitionType.LIST,
            key = listOf("bucket"),
            partitions = listOf(PartitionDefinition(name = "p_a", values = listOf("1", "2"))),
        )
        val result = upFor(table(list))

        result.statements.shouldBeEmpty()
        result.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }
})
