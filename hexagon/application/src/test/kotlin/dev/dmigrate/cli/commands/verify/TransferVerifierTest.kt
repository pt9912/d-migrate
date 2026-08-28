package dev.dmigrate.cli.commands.verify

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.data.ChunkSequence
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.chunkSchemaOf
import dev.dmigrate.verify.ValueCanonicalizationException
import dev.dmigrate.verify.ValueCanonicalizer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * LN-009: Orchestrierung der Quelle↔Ziel-Reconciliation — Match, Divergenz,
 * Reihenfolge-Invarianz, Spalten-Ausschluss, Inkonklusiv-Pfad.
 */
class TransferVerifierTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): DatabaseConnection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    fun chunk(table: String, cols: List<String>, rows: List<Array<Any?>>) =
        DataChunk(table, cols.map { ColumnDescriptor(it, true, null) }, rows, 0)

    fun reader(vararg chunks: DataChunk) = object : DataReader {
        override val dialect = DatabaseDialect.SQLITE
        override fun streamTable(pool: ConnectionPool, table: String, filter: DataFilter?, chunkSize: Int): ChunkSequence =
            object : ChunkSequence {
                override val schema: ChunkSchema = chunkSchemaOf(table, emptyList())
                override fun iterator(): Iterator<DataChunk> = chunks.iterator()
                override fun close() {}
            }
    }

    fun schema(vararg cols: Pair<String, NeutralType>, table: String = "users") = SchemaDefinition(
        name = "t", version = "1.0",
        tables = mapOf(table to TableDefinition(columns = cols.associate { (n, t) -> n to ColumnDefinition(type = t) })),
    )

    val toStringCanon = ValueCanonicalizer { v, _ -> v.toString().toByteArray() }
    val idName = arrayOf("id" to NeutralType.Integer, "name" to NeutralType.Text())

    fun row(vararg v: Any?): Array<Any?> = arrayOf(*v)

    fun verify(
        source: DataReader,
        target: DataReader,
        sourceSchema: SchemaDefinition,
        targetSchema: SchemaDefinition = sourceSchema,
        tables: List<String> = listOf("users"),
        canonicalizer: ValueCanonicalizer = toStringCanon,
    ) = TransferVerifier(canonicalizer).verify(
        tables = tables,
        source = VerifySide(source, pool, sourceSchema),
        target = VerifySide(target, pool, targetSchema),
        filter = null, chunkSize = 10,
    )

    test("identische Daten → allMatch, gleiche Zeilenzahl") {
        val s = schema(*idName)
        val data = chunk("users", listOf("id", "name"), listOf(row(1, "a"), row(2, "b")))
        val report = verify(reader(data), reader(data), s)
        report.allMatch shouldBe true
        report.tables[0].sourceRows shouldBe 2L
        report.tables[0].targetRows shouldBe 2L
    }

    test("Reihenfolge-Invarianz: gleiche Multiset in anderer Reihenfolge matcht") {
        val s = schema(*idName)
        val src = reader(chunk("users", listOf("id", "name"), listOf(row(1, "a"), row(2, "b"))))
        val tgt = reader(chunk("users", listOf("id", "name"), listOf(row(2, "b"), row(1, "a"))))
        verify(src, tgt, s).allMatch shouldBe true
    }

    test("Wert-Divergenz bei gleicher Zeilenzahl → checksum mismatch") {
        val s = schema(*idName)
        val src = reader(chunk("users", listOf("id", "name"), listOf(row(1, "a"))))
        val tgt = reader(chunk("users", listOf("id", "name"), listOf(row(1, "DIFFERENT"))))
        val report = verify(src, tgt, s)
        report.allMatch shouldBe false
        report.tables[0].sourceRows shouldBe report.tables[0].targetRows
    }

    test("Zeilenzahl-Divergenz → kein Match") {
        val s = schema(*idName)
        val src = reader(chunk("users", listOf("id", "name"), listOf(row(1, "a"), row(2, "b"))))
        val tgt = reader(chunk("users", listOf("id", "name"), listOf(row(1, "a"))))
        val report = verify(src, tgt, s)
        report.allMatch shouldBe false
        report.tables[0].sourceRows shouldBe 2L
        report.tables[0].targetRows shouldBe 1L
    }

    test("Cross-Family-Transform (array→json) wird ausgeschlossen, Rest matcht") {
        val src = schema("id" to NeutralType.Integer, "tags" to NeutralType.Array("text"))
        val tgt = schema("id" to NeutralType.Integer, "tags" to NeutralType.Json)
        val sReader = reader(chunk("users", listOf("id", "tags"), listOf(row(1, "irrelevant-array"))))
        val tReader = reader(chunk("users", listOf("id", "tags"), listOf(row(1, """["a"]"""))))
        val report = verify(sReader, tReader, src, tgt)
        report.allMatch shouldBe true
        report.exclusions.map { it.column } shouldBe listOf("tags")
        report.exclusions[0].reason shouldContain "array -> json"
    }

    test("Float-Breiten-Mismatch schließt Spalte aus, Rest matcht") {
        val src = schema("id" to NeutralType.Integer, "amount" to NeutralType.Float(FloatPrecision.SINGLE))
        val tgt = schema("id" to NeutralType.Integer, "amount" to NeutralType.Float(FloatPrecision.DOUBLE))
        val sReader = reader(chunk("users", listOf("id", "amount"), listOf(row(1, 1.5f))))
        val tReader = reader(chunk("users", listOf("id", "amount"), listOf(row(1, 9.9))))
        val report = verify(sReader, tReader, src, tgt)
        report.allMatch shouldBe true
        report.exclusions.map { it.column } shouldBe listOf("amount")
    }

    test("nicht kanonisierbarer Wert → Tabelle inkonklusiv (Fehler, kein stiller Pass)") {
        val s = schema(*idName)
        val boom = ValueCanonicalizer { v, _ ->
            if (v == "boom") throw ValueCanonicalizationException("nope") else v.toString().toByteArray()
        }
        val src = reader(chunk("users", listOf("id", "name"), listOf(row(1, "boom"))))
        val report = verify(src, src, s, canonicalizer = boom)
        report.allMatch shouldBe false
        report.tables[0].error shouldBe "nope"
    }

    test("schema-qualifizierter Tabellenname findet Spalten über Bare-Name") {
        val s = schema(*idName)
        val data = chunk("public.users", listOf("id", "name"), listOf(row(1, "a")))
        val report = verify(reader(data), reader(data), s, tables = listOf("public.users"))
        report.allMatch shouldBe true
    }

    test("nur gemeinsame Spalten werden verglichen (Quelle hat Extra-Spalte)") {
        val src = schema("id" to NeutralType.Integer, "name" to NeutralType.Text(), "extra" to NeutralType.Text())
        val tgt = schema(*idName)
        val sReader = reader(chunk("users", listOf("id", "name", "extra"), listOf(row(1, "a", "X"))))
        val tReader = reader(chunk("users", listOf("id", "name"), listOf(row(1, "a"))))
        verify(sReader, tReader, src, tgt).allMatch shouldBe true
    }
})
