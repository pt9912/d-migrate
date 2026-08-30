package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

/**
 * Der Partitionierungs-Reverse ohne Container.
 *
 * Die Katalogsemantik selbst ist live belegt
 * ([MssqlSchemaReaderIntegrationTest] liest gegen echtes SQL Server). Hier
 * stehen die Abbildungsregeln, die davon unabhängig sind — und die sonst nur im
 * Integrationsmodul geprüft wären, das unter `-PintegrationTests` läuft und
 * damit nicht in die Modul-Abdeckung zählt.
 */
class MssqlPartitionReverseTest : FunSpec({

    fun rig(jdbc: JdbcOperations): Pair<MssqlSchemaReader, ConnectionPool> {
        val conn = mockk<Connection>(relaxUnitFun = true) { every { catalog } returns "shopdb" }
        val pool = mockk<ConnectionPool> { every { borrow() } returns JdbcDatabaseConnection(conn) }
        return MssqlSchemaReader(jdbcFactory = { jdbc }) to pool
    }

    /**
     * Eine Datenbank mit genau einer Tabelle `t` und einer Spalte `id`.
     *
     * Die Partitionsabfrage enthält ebenfalls `FROM sys.tables t`; der
     * Tabellen-Matcher schließt sie deshalb ausdrücklich aus, sonst
     * beantwortete eine Stub-Registrierung beide Abfragen.
     */
    fun jdbcWithOneTable(): JdbcOperations = mockk<JdbcOperations>().also { jdbc ->
        every { jdbc.querySingle(match { it.contains("SCHEMA_NAME()") }) } returns mapOf("schema_name" to "dbo")
        every {
            jdbc.queryList(match { it.contains("FROM sys.tables t") && !it.contains("partition_schemes") }, any())
        } returns listOf(mapOf("table_name" to "t", "schema_name" to "dbo"))
        every { jdbc.queryList(match { it.contains("FROM sys.sequences seq") }, any()) } returns emptyList()
        // Diese Spec deckt Partitionierung ab; Volltext liest sie als leer.
        every {
            jdbc.queryList(match { it.contains("FROM sys.fulltext_index_columns") }, any())
        } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.views v") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.objects o") }, any()) } returns emptyList()
        every {
            jdbc.queryList(match { it.contains("FROM sys.sql_expression_dependencies d") }, any())
        } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.columns c") }, any()) } returns listOf(
            mapOf(
                "column_name" to "id", "type_name" to "int", "max_length" to 4, "precision" to 10, "scale" to 0,
                "is_nullable" to false, "is_identity" to false, "seed_value" to null, "increment_value" to null,
                "is_computed" to false, "computed_definition" to null, "default_definition" to null,
                "column_id" to 1,
            ),
        )
        every { jdbc.queryList(match { it.contains("kc.type = 'PK'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.foreign_keys fk") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.indexes i") && !it.contains("partition_schemes") }, any()) } returns
            emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.check_constraints cc") }, any()) } returns emptyList()
    }

    fun withPartitioning(
        jdbc: JdbcOperations,
        boundaryOnRight: Boolean,
        boundaries: List<Any?>,
    ) {
        every { jdbc.queryList(match { it.contains("sys.partition_schemes") }, any()) } returns listOf(
            mapOf(
                "function_name" to "pf_t", "scheme_name" to "ps_t", "function_id" to 1,
                "boundary_value_on_right" to boundaryOnRight, "column_name" to "id",
            ),
        )
        every { jdbc.queryList(match { it.contains("sys.partition_range_values") }, any()) } returns
            boundaries.map { mapOf("value" to it) }
    }

    test("n boundaries produce n+1 partitions with half-open bounds") {
        val jdbc = jdbcWithOneTable().also { withPartitioning(it, boundaryOnRight = true, boundaries = listOf(100, 200)) }
        val (reader, pool) = rig(jdbc)
        val partitioning = reader.read(pool).schema.tables.getValue("t").partitioning.shouldNotBeNull()

        partitioning.type shouldBe PartitionType.RANGE
        partitioning.key shouldBe listOf("id")
        partitioning.partitions.map { it.name } shouldBe listOf("p1", "p2", "p3")
        partitioning.partitions[0].from shouldBe listOf(PartitionBound.MinValue)
        partitioning.partitions[0].to shouldBe listOf(PartitionBound.Value("100"))
        partitioning.partitions[1].from shouldBe listOf(PartitionBound.Value("100"))
        partitioning.partitions[2].to shouldBe listOf(PartitionBound.MaxValue)
    }

    test("RANGE LEFT keeps the partitioning fact but carries no children") {
        val jdbc = jdbcWithOneTable().also { withPartitioning(it, boundaryOnRight = false, boundaries = listOf(10)) }
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)

        // Nicht `null`: MssqlRebuildRenderer blockt einen Tabellen-Neubau auf
        // `partitioning != null`. Mit `null` waere genau dieser Waechter blind,
        // und der Neubau raeumte die Partitionierung still ab.
        val partitioning = result.schema.tables.getValue("t").partitioning.shouldNotBeNull()
        partitioning.partitions.shouldBeEmpty()
        val note = result.notes.first { it.code == "R347" }
        note.severity shouldBe SchemaReadSeverity.ACTION_REQUIRED
        note.message shouldContain "RANGE LEFT"
    }

    test("the synthesized child names are reported, not silently invented") {
        val jdbc = jdbcWithOneTable().also { withPartitioning(it, boundaryOnRight = true, boundaries = listOf(1)) }
        val (reader, pool) = rig(jdbc)
        val note = reader.read(pool).notes.first { it.code == "R346" }
        note.severity shouldBe SchemaReadSeverity.INFO
        note.message shouldContain "p1"
    }

    test("a table without a partition scheme reads as unpartitioned") {
        val jdbc = jdbcWithOneTable()
        every { jdbc.queryList(match { it.contains("sys.partition_schemes") }, any()) } returns
            emptyList<Map<String, Any?>>()
        val (reader, pool) = rig(jdbc)
        reader.read(pool).schema.tables.getValue("t").partitioning.shouldBeNull()
    }

    test("a boolean boundary renders in the PostgreSQL form, not as 1/0") {
        val jdbc = jdbcWithOneTable().also { withPartitioning(it, boundaryOnRight = true, boundaries = listOf(true)) }
        val (reader, pool) = rig(jdbc)
        val partitioning = reader.read(pool).schema.tables.getValue("t").partitioning.shouldNotBeNull()
        partitioning.partitions[0].to shouldBe listOf(PartitionBound.Value("true"))
    }
})
