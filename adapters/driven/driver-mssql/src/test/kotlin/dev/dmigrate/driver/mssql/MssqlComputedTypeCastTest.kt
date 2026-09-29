package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType
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
 * P13: der deklarierte Typ einer berechneten SQL-Server-Spalte ueberlebt den
 * Rundweg — der Generator haengt an den Ausdruck eine `CAST`-Huelle auf den
 * deklarierten Typ, der Reader fuehrt die aeusserste `CONVERT`-Huelle zurueck,
 * **genau dann** wenn ihr Typ dem Spaltentyp entspricht, genau zwei Argumente
 * stehen und nach ihr nichts folgt (gemessen an SQL Server 2025, Plan
 * Reader-Treue 4, Messungen 1 und 2).
 *
 * Der Katalogtext ist dabei geklammert (`CONVERT([decimal](14,2),…)`); der
 * Vergleich arbeitet auf der **normalisierten** Form (P12) — auf dem rohen
 * Text traefe der Typ-Token den Spaltentyp nicht. Die Hash-Partitionserkennung
 * liest denselben Katalogtext weiter in Serverform und bleibt unberuehrt.
 */
class MssqlComputedTypeCastTest : FunSpec({

    fun readerOver(
        computedDefinition: String,
        typeName: String,
        maxLength: Int?,
        precision: Int?,
        scale: Int?,
    ): Pair<MssqlSchemaReader, ConnectionPool> {
        val jdbc = mockk<JdbcOperations>()
        every { jdbc.querySingle(match { it.contains("SCHEMA_NAME()") }) } returns mapOf("schema_name" to "dbo")
        every {
            jdbc.queryList(match { it.contains("FROM sys.tables t") && !it.contains("partition_schemes") }, any())
        } returns listOf(mapOf("table_name" to "line", "schema_name" to "dbo"))
        every { jdbc.queryList(match { it.contains("FROM sys.columns c") }, any()) } returns listOf(
            mapOf(
                "column_name" to "line_total", "type_name" to typeName, "max_length" to maxLength,
                "precision" to precision, "scale" to scale,
                "is_nullable" to true, "is_identity" to false, "seed_value" to null, "increment_value" to null,
                "is_computed" to true, "computed_definition" to computedDefinition,
                "computed_persisted" to true, "default_definition" to null, "column_id" to 1,
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

    /** Liest den Ausdruck aus dem **rohen** Katalogtext — genau wie der Reader. */
    fun expressionOf(
        computedDefinition: String,
        typeName: String,
        maxLength: Int? = 9,
        precision: Int? = null,
        scale: Int? = null,
    ): String {
        val (reader, pool) = readerOver(computedDefinition, typeName, maxLength, precision, scale)
        val column = reader.read(pool).schema.tables.getValue("line").columns.getValue("line_total")
        val computed = column.generation as? ColumnGeneration.Computed
        computed.shouldNotBeNull()
        return computed.expression
    }

    // ── Messung 2: die Huelle auf den Spaltentyp geht, alles andere bleibt ──

    test("the generator's cast comes back stripped, the declared type is carried in the column") {
        // `(CONVERT([decimal](14,2),[quantity]*[unit_price]))`, Spalte decimal(14,2)
        expressionOf("(CONVERT([decimal](14,2),[quantity]*[unit_price]))", "decimal", 9, 14, 2) shouldBe
            "quantity*unit_price"
    }

    test("an expression without a cast stays as it is") {
        // `([quantity]*[unit_price])`, Spalte decimal(25,4) — der Ausdruckstyp
        expressionOf("([quantity]*[unit_price])", "decimal", 9, 25, 4) shouldBe "quantity*unit_price"
    }

    test("a cast on another type stays — as an inner hull of a nested cast") {
        // `CAST(CAST(q * p AS decimal(14,2)) AS decimal(12,2))`, Spalte decimal(12,2):
        // aeusserste Huelle (Typ = Spaltentyp) entfernt, Autorenhuelle bleibt.
        expressionOf(
            "(CONVERT([decimal](12,2),CONVERT([decimal](14,2),[quantity]*[unit_price])))",
            "decimal",
            9,
            12,
            2,
        ) shouldBe "CONVERT(decimal(14,2),quantity*unit_price)"
    }

    test("a nested cast loses only its outermost hull") {
        expressionOf(
            "(CONVERT([varchar](12),CONVERT([varchar](10),[id])))",
            "varchar",
            12,
        ) shouldBe "CONVERT(varchar(10),id)"
    }

    test("a cast with a style argument stays") {
        // Das Stilargument liegt eingeklammert als drittes Argument.
        expressionOf(
            "(CONVERT([varchar](30),[created_at],(126)))",
            "varchar",
            30,
        ) shouldBe "CONVERT(varchar(30),created_at,(126))"
    }

    test("a length-less varchar stays — its token does not name the column type") {
        // `CAST(unit_price AS varchar)` leitet varchar(30) ab, legt `[varchar]` ab.
        expressionOf("(CONVERT([varchar],[unit_price]))", "varchar", 30) shouldBe "CONVERT(varchar,unit_price)"
    }

    test("an author cast on the same type is indistinguishable and comes back stripped") {
        expressionOf("(CONVERT([decimal](12,2),[quantity]))", "decimal", 9, 12, 2) shouldBe "quantity"
    }

    test("a datetime2 hull is stripped against the column precision") {
        expressionOf("(CONVERT([datetime2](3),[created_at]))", "datetime2", 8, null, 3) shouldBe "created_at"
        // Ohne Praezision im Katalogtext (Autor schrieb `datetime2`): der
        // Server leitet trotzdem datetime2(7) ab — textuell kein Treffer.
        expressionOf("(CONVERT([datetime2],[created_at]))", "datetime2", 8, null, 7) shouldBe
            "CONVERT(datetime2,created_at)"
    }

    test("a convert that does not span the whole expression stays") {
        // Nach der Huelle folgt etwas: kein Server-Cast.
        expressionOf("(CONVERT([decimal](14,2),[quantity])*[unit_price])", "decimal", 9, 14, 2) shouldBe
            "CONVERT(decimal(14,2),quantity)*unit_price"
    }

    test("a unicode literal inside the expression survives the comparison") {
        expressionOf("(CONVERT([varchar](30),N'[x]'+[id]))", "varchar", 30) shouldBe "'[x]'+id"
    }

    // ── Der Zieltyp der Generatorhuelle ──

    test("the cast target carries the derived precision explicitly") {
        // Der Reverse vergleicht gegen den Katalogtext; `DATETIME2` allein
        // traefe `datetime2(7)` nicht.
        MssqlTypeMapper().castTargetSql(NeutralType.DateTime(timezone = false)) shouldBe "DATETIME2(7)"
        MssqlTypeMapper().castTargetSql(NeutralType.DateTime(timezone = true)) shouldBe "DATETIMEOFFSET(7)"
        MssqlTypeMapper().castTargetSql(NeutralType.Time) shouldBe "TIME(7)"
        MssqlTypeMapper().castTargetSql(NeutralType.Decimal(14, 2)) shouldBe "DECIMAL(14,2)"
        MssqlTypeMapper().castTargetSql(NeutralType.Text(maxLength = null)) shouldBe "NVARCHAR(MAX)"
        // IDENTITY(1,1) waere im CAST ein Syntaxfehler.
        MssqlTypeMapper().castTargetSql(NeutralType.Identifier(autoIncrement = true)) shouldBe "INT"
    }

    test("the cast target and the catalog text name the same type") {
        // Roundtrip-Zusicherung, Fall fuer Fall: was der Generator als Zieltyp
        // schreibt, liest der Reader in derselben Schreibweise aus dem Katalog
        // (Gross-/Kleinschreibung ausgenommen, die gleicht der Vergleich aus).
        val pairs = listOf(
            MssqlTypeMapper().castTargetSql(NeutralType.Decimal(14, 2)) to
                MssqlServerCast.catalogTypeText("decimal", 9, 14, 2),
            MssqlTypeMapper().castTargetSql(NeutralType.Text(maxLength = 30)) to
                MssqlServerCast.catalogTypeText("nvarchar", 60, null, null),
            MssqlTypeMapper().castTargetSql(NeutralType.DateTime(timezone = false)) to
                MssqlServerCast.catalogTypeText("datetime2", 8, null, 7),
        )
        pairs.forEach { (target, catalog) -> target.lowercase() shouldBe catalog.lowercase() }
        MssqlServerCast.catalogTypeText("nvarchar", -1, null, null) shouldBe "nvarchar(max)"
        MssqlServerCast.catalogTypeText("int", 4, 10, 0) shouldBe "int"
    }
})
