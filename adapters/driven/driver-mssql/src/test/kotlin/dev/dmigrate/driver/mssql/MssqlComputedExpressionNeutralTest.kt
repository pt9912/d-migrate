package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.RawSqlExpressionPortability
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

/**
 * Der Berechnungsausdruck einer Spalte geht in **neutraler** Schreibweise ins
 * Modell — dieselbe Regel wie beim CHECK (`MssqlTypeMapping.normalizeExpression`).
 *
 * Gemessen an SQL Server 2025: `sys.computed_columns.definition` liefert
 * `([quantity]*[unit_price])`. Unveraendert weitergereicht scheitert daran
 * jedes andere Ziel — PostgreSQL mit `syntax error at or near "["`, MySQL mit
 * `ERROR 1064` (beide Zellen der Compare-Matrix standen deshalb auf
 * `APPLY-FAIL`), und die Portabilitaetspruefung erkennt Klammer-Quoting
 * bewusst nicht als Marker (`[` steht ebenso in JSON-Pfaden).
 *
 * Die **Hash-Partitionserkennung** liest denselben Katalogtext weiter in
 * Serverform; sie haengt an der Abfrage, nicht am Modell
 * ([MssqlHashPartitionRecognition]).
 */
class MssqlComputedExpressionNeutralTest : FunSpec({

    fun readerOver(computedDefinition: String): Pair<MssqlSchemaReader, ConnectionPool> {
        val jdbc = mockk<JdbcOperations>()
        every { jdbc.querySingle(match { it.contains("SCHEMA_NAME()") }) } returns mapOf("schema_name" to "dbo")
        every {
            jdbc.queryList(match { it.contains("FROM sys.tables t") && !it.contains("partition_schemes") }, any())
        } returns listOf(mapOf("table_name" to "line", "schema_name" to "dbo"))
        every { jdbc.queryList(match { it.contains("FROM sys.columns c") }, any()) } returns listOf(
            mapOf(
                "column_name" to "quantity", "type_name" to "int", "max_length" to 4, "precision" to 10, "scale" to 0,
                "is_nullable" to false, "is_identity" to false, "seed_value" to null, "increment_value" to null,
                "is_computed" to false, "computed_definition" to null, "default_definition" to null, "column_id" to 1,
            ),
            mapOf(
                "column_name" to "line_total", "type_name" to "decimal", "max_length" to 9,
                "precision" to 14, "scale" to 2,
                "is_nullable" to true, "is_identity" to false, "seed_value" to null, "increment_value" to null,
                "is_computed" to true, "computed_definition" to computedDefinition,
                "computed_persisted" to true, "default_definition" to null, "column_id" to 2,
            ),
        )
        for (fragment in listOf(
            "FROM sys.sequences seq", "FROM sys.fulltext_index_columns", "FROM sys.views v",
            "FROM sys.objects o", "FROM sys.sql_expression_dependencies d", "kc.type = 'PK'", "kc.type = 'UQ'",
            "FROM sys.foreign_keys fk", "FROM sys.check_constraints cc", "sys.partition_schemes",
        )) {
            every { jdbc.queryList(match { it.contains(fragment) }, any()) } returns emptyList()
        }
        every { jdbc.queryList(match { it.contains("FROM sys.indexes i") && !it.contains("partition_schemes") }, any()) } returns
            emptyList()
        val conn = mockk<Connection>(relaxUnitFun = true) { every { catalog } returns "shopdb" }
        val pool = mockk<ConnectionPool> { every { borrow() } returns JdbcDatabaseConnection(conn) }
        return MssqlSchemaReader(jdbcFactory = { jdbc }) to pool
    }

    fun expressionOf(computedDefinition: String): String {
        val (reader, pool) = readerOver(computedDefinition)
        val column = reader.read(pool).schema.tables.getValue("line").columns.getValue("line_total")
        val computed = column.generation as? ColumnGeneration.Computed
        computed.shouldNotBeNull()
        return computed.expression
    }

    test("the bracket quoting and the server's outer parenthesis are gone") {
        expressionOf("([quantity]*[unit_price])") shouldBe "quantity*unit_price"
    }

    test("a column that needs quoting keeps it, in the neutral spelling") {
        expressionOf("([Menge]*[Preis])") shouldBe "\"Menge\"*\"Preis\""
    }

    test("a Unicode literal loses its prefix, a literal keeps its content") {
        expressionOf("(N'n'+CONVERT([varchar](10),[id]))") shouldBe "'n'+CONVERT(varchar(10),id)"
        expressionOf("('[x]'+[id])") shouldBe "'[x]'+id"
    }

    test("the neutral expression is portable for every other target") {
        val neutral = expressionOf("([quantity]*[unit_price])")
        for (target in listOf(DatabaseDialect.POSTGRESQL, DatabaseDialect.MYSQL, DatabaseDialect.SQLITE)) {
            RawSqlExpressionPortability.computedRefusal("line_total", neutral, target) shouldBe null
        }
    }

    test("the predicate of a filtered index comes in the same spelling") {
        // Derselbe Katalog, dieselbe Oberflaechensyntax: ohne die Regel trug
        // die erzeugte DDL `WHERE ([shipped_at] IS NULL)`, und PostgreSQL
        // lehnte sie ab (`syntax error at or near "["` — in der Compare-Matrix
        // die Zelle SQL Server -> PostgreSQL).
        MssqlTypeMapping.normalizeExpression("([shipped_at] IS NULL)") shouldBe "shipped_at IS NULL"
        MssqlTypeMapping.normalizeExpression("([Menge]>(0))") shouldBe "\"Menge\">(0)"
    }

    test("the hash-partition recognition still reads the server form") {
        // Sie haengt am Katalogtext, nicht am Modell: normalisiert erkennt sie
        // ihre eigene Emulation nicht mehr wieder.
        MssqlHashPartitionRecognition.recognize(
            "(abs(checksum([customer_id])%(4)))",
            listOf("1", "2", "3"),
        ).shouldNotBeNull()
    }
})
