package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TargetColumn
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSetMetaData
import java.sql.Types

/**
 * Wann der BulkCopy-Weg genommen wird und was er dem Treiber uebergibt.
 *
 * Die Entscheidung ist die Sicherung: BulkCopy ist ein roher Wertstrom, und
 * jede Faehigkeit des INSERT-Wegs, die er nicht traegt, muss ihn ausschliessen
 * — sonst waere er kein schnellerer Weg zum selben Ergebnis.
 */
class MssqlBulkCopyFastPathTest : FunSpec({

    fun column(name: String, type: Int) = TargetColumn(name = name, nullable = true, jdbcType = type)

    val plainColumns = listOf(
        column("id", Types.INTEGER),
        column("name", Types.NVARCHAR),
        column("amount", Types.DECIMAL),
    )
    val noGeometry: (TargetColumn) -> Boolean = { false }

    test("plain scalar columns under ABORT take the bulk path") {
        MssqlBulkCopyFastPath.isEligible(plainColumns, OnConflict.ABORT, noGeometry) shouldBe true
    }

    test("the MERGE conflict modes do not — BulkCopy has no ON CONFLICT") {
        for (mode in listOf(OnConflict.SKIP, OnConflict.UPDATE)) {
            withClue("$mode") {
                MssqlBulkCopyFastPath.isEligible(plainColumns, mode, noGeometry) shouldBe false
            }
        }
    }

    test("a geometry column does not — its value is built by SQL, not streamed") {
        MssqlBulkCopyFastPath.isEligible(plainColumns, OnConflict.ABORT) { it.name == "amount" } shouldBe false
    }

    test("a type outside the safe set does not") {
        // `VARBINARY` ist bewusst draussen: dort liegt die Geometrie-WKB-Naht,
        // und der INSERT-Weg bleibt fuer sie unveraendert korrekt.
        for (type in listOf(Types.VARBINARY, Types.BINARY, Types.OTHER, Types.ARRAY, Types.CLOB, Types.SQLXML)) {
            withClue("${'$'}type") {
                MssqlBulkCopyFastPath.isEligible(
                    plainColumns + column("x", type), OnConflict.ABORT, noGeometry,
                ) shouldBe false
            }
        }
    }

    test("an empty column list does not") {
        MssqlBulkCopyFastPath.isEligible(emptyList(), OnConflict.ABORT, noGeometry) shouldBe false
    }

    test("precision and scale come from the server, not from a guess") {
        // `TargetColumn` fuehrt sie nicht, und `ISQLServerBulkData` verlangt sie
        // je Spalte — falsch geraten schneidet der Treiber Werte ab.
        val meta = mockk<ResultSetMetaData>()
        every { meta.getPrecision(1) } returns 10
        every { meta.getScale(1) } returns 0
        every { meta.getPrecision(2) } returns 100
        every { meta.getScale(2) } returns 0
        every { meta.getPrecision(3) } returns 18
        every { meta.getScale(3) } returns 2
        val stmt = mockk<PreparedStatement>(relaxed = true)
        every { stmt.metaData } returns meta
        val conn = mockk<Connection>()
        every { conn.prepareStatement(any<String>()) } returns stmt

        MssqlBulkCopyFastPath.columnMeta(conn, "[dbo].[t]", plainColumns) shouldContainExactly listOf(
            MssqlBulkCopyFastPath.ColumnMeta("id", Types.INTEGER, 10, 0),
            MssqlBulkCopyFastPath.ColumnMeta("name", Types.NVARCHAR, 100, 0),
            MssqlBulkCopyFastPath.ColumnMeta("amount", Types.DECIMAL, 18, 2),
        )
    }

    test("the row stream normalises foreign driver wrappers, like the INSERT path") {
        // Ein `java.sql.Array` aus PostgreSQL landete sonst java-serialisiert
        // in der Zielspalte.
        val array = mockk<java.sql.Array>()
        every { array.baseType } returns Types.VARCHAR
        every { array.array } returns arrayOf("a", "b")
        val data = MssqlBulkChunkData(
            listOf(MssqlBulkCopyFastPath.ColumnMeta("tags", Types.NVARCHAR, 100, 0)),
            listOf(arrayOf<Any?>(array), arrayOf<Any?>(null)),
        )

        data.next() shouldBe true
        data.rowData.single().shouldBeInstanceOfString()
        data.next() shouldBe true
        data.rowData.single() shouldBe null
        data.next() shouldBe false
    }

    test("the stream reports the columns it was given") {
        val data = MssqlBulkChunkData(
            listOf(
                MssqlBulkCopyFastPath.ColumnMeta("id", Types.INTEGER, 10, 0),
                MssqlBulkCopyFastPath.ColumnMeta("amount", Types.DECIMAL, 18, 2),
            ),
            listOf(arrayOf<Any?>(1, 2)),
        )
        data.columnOrdinals shouldContainExactly setOf(1, 2)
        data.getColumnName(1) shouldBe "id"
        data.getColumnType(2) shouldBe Types.DECIMAL
        data.getPrecision(2) shouldBe 18
        data.getScale(2) shouldBe 2
    }
})

private fun Any?.shouldBeInstanceOfString() {
    (this is String) shouldBe true
}
