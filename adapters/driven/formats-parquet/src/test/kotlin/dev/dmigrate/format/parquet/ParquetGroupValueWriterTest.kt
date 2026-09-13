package dev.dmigrate.format.parquet

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [ParquetGroupValueWriter.writeColumn] traegt fuer jeden `NeutralType`
 * eine kleine `when`-Koerzierungstabelle — die "natuerliche" JDBC-Form
 * jedes Typs war laengst durch Round-Trip-Tests abgedeckt, die
 * *anderen* Java-Typen, die ein JDBC-Treiber fuer denselben Spaltentyp
 * ebenso liefert (`Short`/`Byte` statt `Int`, `java.sql.Date` statt
 * `LocalDate`, ...), standen bei 38 % Zeilen-Deckung. Kein
 * Hadoop-Hindernis — reine, pure Koerzierungsfunktionen.
 *
 * Ein Feld pro Testfall, eine gemeinsame [Group] fuer alle: `append`
 * ist namensbasiert, das Zurücklesen [Group.getInteger] & Co. nur
 * index-basiert — der Index kommt aus [Group.getType].
 */
class ParquetGroupValueWriterTest : FunSpec({

    fun schema(vararg columns: Pair<String, NeutralType>) = ChunkSchema(
        table = "t",
        origin = SchemaOrigin.JDBC_METADATA,
        columns = columns.map { (name, type) -> ChunkColumnSchema(name, nullable = true, neutralType = type) },
    )

    fun newGroup(vararg columns: Pair<String, NeutralType>): Group {
        val messageType = ChunkSchemaToParquetMessageType.convert(schema(*columns))
        return SimpleGroupFactory(messageType).newGroup()
    }

    fun fieldIndex(group: Group, name: String): Int = group.type.getFieldIndex(name)

    // ─── null ──────────────────────────────────────────────────────

    test("null wird uebersprungen — das OPTIONAL-Feld bleibt unbelegt") {
        val group = newGroup("v" to NeutralType.Integer)
        ParquetGroupValueWriter.writeColumn(group, "v", NeutralType.Integer, null)
        group.getFieldRepetitionCount(fieldIndex(group, "v")) shouldBe 0
    }

    // ─── Boolean ───────────────────────────────────────────────────

    test("Boolean-Spalte koerziert Boolean, Number und String") {
        val g1 = newGroup("v" to NeutralType.BooleanType)
        ParquetGroupValueWriter.writeColumn(g1, "v", NeutralType.BooleanType, true)
        g1.getBoolean(fieldIndex(g1, "v"), 0) shouldBe true

        val g2 = newGroup("v" to NeutralType.BooleanType)
        ParquetGroupValueWriter.writeColumn(g2, "v", NeutralType.BooleanType, 1)
        g2.getBoolean(fieldIndex(g2, "v"), 0) shouldBe true

        val g3 = newGroup("v" to NeutralType.BooleanType)
        ParquetGroupValueWriter.writeColumn(g3, "v", NeutralType.BooleanType, "true")
        g3.getBoolean(fieldIndex(g3, "v"), 0) shouldBe true

        val g4 = newGroup("v" to NeutralType.BooleanType)
        shouldThrow<IllegalStateException> {
            ParquetGroupValueWriter.writeColumn(g4, "v", NeutralType.BooleanType, listOf(1))
        }
    }

    // ─── Integer-Familie ───────────────────────────────────────────

    test("Integer-Spalte koerziert Long, Short, Byte, Number und String") {
        for ((value, expected) in listOf<Pair<Any, Int>>(
            7 to 7, 7L to 7, 7.toShort() to 7, 7.toByte() to 7, 7.0 to 7, "7" to 7,
        )) {
            val g = newGroup("v" to NeutralType.Integer)
            ParquetGroupValueWriter.writeColumn(g, "v", NeutralType.Integer, value)
            g.getInteger(fieldIndex(g, "v"), 0) shouldBe expected
        }
        val g = newGroup("v" to NeutralType.Integer)
        shouldThrow<IllegalStateException> {
            ParquetGroupValueWriter.writeColumn(g, "v", NeutralType.Integer, true)
        }
    }

    test("BigInteger-Spalte koerziert Number und String zu Long") {
        for ((value, expected) in listOf<Pair<Any, Long>>(42 to 42L, 42.0 to 42L, "42" to 42L)) {
            val g = newGroup("v" to NeutralType.BigInteger)
            ParquetGroupValueWriter.writeColumn(g, "v", NeutralType.BigInteger, value)
            g.getLong(fieldIndex(g, "v"), 0) shouldBe expected
        }
        val g = newGroup("v" to NeutralType.BigInteger)
        shouldThrow<IllegalStateException> {
            ParquetGroupValueWriter.writeColumn(g, "v", NeutralType.BigInteger, true)
        }
    }

    // ─── Float/Double ──────────────────────────────────────────────

    test("Float-Spalte (SINGLE) koerziert Number und String") {
        val single = NeutralType.Float(FloatPrecision.SINGLE)
        for (value in listOf<Any>(1.5f, 1.5, "1.5")) {
            val g = newGroup("v" to single)
            ParquetGroupValueWriter.writeColumn(g, "v", single, value)
            g.getFloat(fieldIndex(g, "v"), 0) shouldBe 1.5f
        }
    }

    test("Float-Spalte (DOUBLE) koerziert Number und String") {
        val double = NeutralType.Float(FloatPrecision.DOUBLE)
        for (value in listOf<Any>(2.5, 2.5f, "2.5")) {
            val g = newGroup("v" to double)
            ParquetGroupValueWriter.writeColumn(g, "v", double, value)
            g.getDouble(fieldIndex(g, "v"), 0) shouldBe 2.5
        }
    }

    // ─── Decimal — drei Physik-Formen ───────────────────────────────

    test("Decimal koerziert BigDecimal, Number und String, physikalisch nach Precision") {
        // precision <= 9 -> INT32
        val small = NeutralType.Decimal(precision = 5, scale = 2)
        val g1 = newGroup("v" to small)
        ParquetGroupValueWriter.writeColumn(g1, "v", small, "12.34")
        g1.getInteger(fieldIndex(g1, "v"), 0) shouldBe 1234

        // precision <= 18 -> INT64
        val medium = NeutralType.Decimal(precision = 12, scale = 2)
        val g2 = newGroup("v" to medium)
        ParquetGroupValueWriter.writeColumn(g2, "v", medium, 99)
        g2.getLong(fieldIndex(g2, "v"), 0) shouldBe 9900L

        // precision > 18 -> FIXED_LEN_BYTE_ARRAY
        val large = NeutralType.Decimal(precision = 30, scale = 2)
        val g3 = newGroup("v" to large)
        ParquetGroupValueWriter.writeColumn(g3, "v", large, BigDecimal("1.00"))
        g3.getBinary(fieldIndex(g3, "v"), 0).bytes.isNotEmpty() shouldBe true
    }

    test("Decimal wirft fuer einen nicht koerzierbaren Wert") {
        val d = NeutralType.Decimal(precision = 5, scale = 2)
        val g = newGroup("v" to d)
        shouldThrow<IllegalStateException> {
            ParquetGroupValueWriter.writeColumn(g, "v", d, true)
        }
    }

    // ─── Binary ────────────────────────────────────────────────────

    test("Binary koerziert ByteArray, ByteBuffer und String") {
        val g1 = newGroup("v" to NeutralType.Binary)
        ParquetGroupValueWriter.writeColumn(g1, "v", NeutralType.Binary, byteArrayOf(1, 2, 3))
        g1.getBinary(fieldIndex(g1, "v"), 0).bytes shouldBe byteArrayOf(1, 2, 3)

        val g2 = newGroup("v" to NeutralType.Binary)
        ParquetGroupValueWriter.writeColumn(g2, "v", NeutralType.Binary, ByteBuffer.wrap(byteArrayOf(4, 5)))
        g2.getBinary(fieldIndex(g2, "v"), 0).bytes shouldBe byteArrayOf(4, 5)

        val g3 = newGroup("v" to NeutralType.Binary)
        ParquetGroupValueWriter.writeColumn(g3, "v", NeutralType.Binary, "ab")
        g3.getBinary(fieldIndex(g3, "v"), 0).bytes shouldBe "ab".toByteArray()
    }

    // ─── Date/Time/DateTime — die "anderen" JDBC-Formen ─────────────

    test("Date koerziert java.sql.Date und String zu Epoch-Tagen") {
        val expected = LocalDate.of(2026, 1, 15).toEpochDay().toInt()

        val g1 = newGroup("v" to NeutralType.Date)
        ParquetGroupValueWriter.writeColumn(g1, "v", NeutralType.Date, java.sql.Date.valueOf("2026-01-15"))
        g1.getInteger(fieldIndex(g1, "v"), 0) shouldBe expected

        val g2 = newGroup("v" to NeutralType.Date)
        ParquetGroupValueWriter.writeColumn(g2, "v", NeutralType.Date, "2026-01-15")
        g2.getInteger(fieldIndex(g2, "v"), 0) shouldBe expected
    }

    test("Time koerziert java.sql.Time und String zu Mikrosekunden des Tages (INT64)") {
        // TIME(MICROS) ist physisch INT64 (Parquet-Spezifikation) -- ein
        // frueherer INT32-Ansatz scheiterte schon am Schema-Bau, siehe
        // ChunkSchemaToParquetMessageType. Ausserdem passen 86 400 000 000
        // Mikrosekunden/Tag ohnehin nicht in Int.
        val g1 = newGroup("v" to NeutralType.Time)
        ParquetGroupValueWriter.writeColumn(g1, "v", NeutralType.Time, java.sql.Time.valueOf("10:30:00"))
        g1.getLong(fieldIndex(g1, "v"), 0) shouldBe LocalTime.of(10, 30, 0).toNanoOfDay() / 1_000L

        val g2 = newGroup("v" to NeutralType.Time)
        ParquetGroupValueWriter.writeColumn(g2, "v", NeutralType.Time, "10:30:00")
        g2.getLong(fieldIndex(g2, "v"), 0) shouldBe LocalTime.of(10, 30, 0).toNanoOfDay() / 1_000L
    }

    test("DateTime ohne Zeitzone koerziert OffsetDateTime, LocalDateTime, java.sql.Timestamp und String") {
        val dt = NeutralType.DateTime(timezone = false)
        val instant = Instant.parse("2026-01-15T10:30:00Z")
        val expectedMicros = instant.epochSecond * 1_000_000L + instant.nano / 1_000L

        val g1 = newGroup("v" to dt)
        ParquetGroupValueWriter.writeColumn(g1, "v", dt, instant.atOffset(ZoneOffset.UTC))
        g1.getLong(fieldIndex(g1, "v"), 0) shouldBe expectedMicros

        val g2 = newGroup("v" to dt)
        ParquetGroupValueWriter.writeColumn(g2, "v", dt, LocalDateTime.of(2026, 1, 15, 10, 30, 0))
        g2.getLong(fieldIndex(g2, "v"), 0) shouldBe expectedMicros

        val g3 = newGroup("v" to dt)
        ParquetGroupValueWriter.writeColumn(g3, "v", dt, java.sql.Timestamp.from(instant))
        g3.getLong(fieldIndex(g3, "v"), 0) shouldBe expectedMicros

        // String ohne Zeitzonen-Flag -> als LocalDateTime interpretiert (UTC).
        val g4 = newGroup("v" to dt)
        ParquetGroupValueWriter.writeColumn(g4, "v", dt, "2026-01-15T10:30:00")
        g4.getLong(fieldIndex(g4, "v"), 0) shouldBe expectedMicros
    }

    test("DateTime mit Zeitzone interpretiert einen String als OffsetDateTime") {
        val dt = NeutralType.DateTime(timezone = true)
        val instant = Instant.parse("2026-01-15T10:30:00Z")
        val expectedMicros = instant.epochSecond * 1_000_000L + instant.nano / 1_000L

        val g = newGroup("v" to dt)
        ParquetGroupValueWriter.writeColumn(g, "v", dt, "2026-01-15T10:30:00Z")
        g.getLong(fieldIndex(g, "v"), 0) shouldBe expectedMicros
    }

    test("DateTime wirft fuer einen nicht koerzierbaren Wert") {
        val dt = NeutralType.DateTime(timezone = false)
        val g = newGroup("v" to dt)
        shouldThrow<IllegalStateException> {
            ParquetGroupValueWriter.writeColumn(g, "v", dt, 42)
        }
    }

    // ─── UUID ──────────────────────────────────────────────────────

    test("UUID koerziert java.util.UUID und String zu 16 Bytes") {
        val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

        val g1 = newGroup("v" to NeutralType.Uuid)
        ParquetGroupValueWriter.writeColumn(g1, "v", NeutralType.Uuid, uuid)
        g1.getBinary(fieldIndex(g1, "v"), 0).bytes.size shouldBe 16

        val g2 = newGroup("v" to NeutralType.Uuid)
        ParquetGroupValueWriter.writeColumn(g2, "v", NeutralType.Uuid, uuid.toString())
        g2.getBinary(fieldIndex(g2, "v"), 0).bytes shouldBe g1.getBinary(fieldIndex(g1, "v"), 0).bytes
    }

    test("UUID wirft fuer einen nicht koerzierbaren Wert") {
        val g = newGroup("v" to NeutralType.Uuid)
        shouldThrow<IllegalStateException> {
            ParquetGroupValueWriter.writeColumn(g, "v", NeutralType.Uuid, 42)
        }
    }
})
