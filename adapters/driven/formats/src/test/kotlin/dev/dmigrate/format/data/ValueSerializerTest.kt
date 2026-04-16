package dev.dmigrate.format.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import java.util.UUID
import java.sql.Date as SqlDate
import java.sql.Time as SqlTime

/**
 * Verifiziert die §6.4.1 Java-Klasse → SerializedValue Mapping-Tabelle aus
 * dem docs/archive/implementation-plan-0.3.0.md zeilenweise.
 */
class ValueSerializerTest : FunSpec({

    val serializer = ValueSerializer()

    test("null → SerializedValue.Null") {
        serializer.serialize("t", "c", null) shouldBe SerializedValue.Null
    }

    test("String → Text") {
        serializer.serialize("t", "c", "hello") shouldBe SerializedValue.Text("hello")
    }

    test("Boolean → Bool") {
        serializer.serialize("t", "c", true) shouldBe SerializedValue.Bool(true)
        serializer.serialize("t", "c", false) shouldBe SerializedValue.Bool(false)
    }

    test("Byte/Short/Int/Long → Integer") {
        serializer.serialize("t", "c", 1.toByte()) shouldBe SerializedValue.Integer(1)
        serializer.serialize("t", "c", 2.toShort()) shouldBe SerializedValue.Integer(2)
        serializer.serialize("t", "c", 3) shouldBe SerializedValue.Integer(3)
        serializer.serialize("t", "c", 4L) shouldBe SerializedValue.Integer(4)
    }

    test("Float/Double → FloatingPoint") {
        serializer.serialize("t", "c", 1.5f) shouldBe SerializedValue.FloatingPoint(1.5)
        serializer.serialize("t", "c", 2.5) shouldBe SerializedValue.FloatingPoint(2.5)
    }

    test("BigInteger → PreciseInteger (raw BigInteger for precision)") {
        val big = BigInteger("123456789012345678901234567890")
        serializer.serialize("t", "c", big) shouldBe SerializedValue.PreciseInteger(big)
    }

    test("BigDecimal → PreciseDecimal (toPlainString for stability)") {
        val dec = BigDecimal("12345.6789")
        serializer.serialize("t", "c", dec) shouldBe SerializedValue.PreciseDecimal("12345.6789")
    }

    test("BigDecimal preserves trailing zeros via toPlainString") {
        val dec = BigDecimal("100.00")
        serializer.serialize("t", "c", dec) shouldBe SerializedValue.PreciseDecimal("100.00")
    }

    test("java.sql.Date → ISO 8601") {
        val d = SqlDate.valueOf("2024-01-15")
        serializer.serialize("t", "c", d) shouldBe SerializedValue.Text("2024-01-15")
    }

    test("java.sql.Time → ISO 8601") {
        val t = SqlTime.valueOf("14:30:00")
        serializer.serialize("t", "c", t) shouldBe SerializedValue.Text("14:30:00")
    }

    test("java.sql.Timestamp → ISO 8601 local datetime") {
        val ts = Timestamp.valueOf("2024-01-15 14:30:00")
        val result = serializer.serialize("t", "c", ts) as SerializedValue.Text
        result.value shouldContain "2024-01-15T14:30"
    }

    test("LocalDate → ISO 8601") {
        serializer.serialize("t", "c", LocalDate.of(2024, 1, 15)) shouldBe SerializedValue.Text("2024-01-15")
    }

    test("LocalTime → ISO 8601") {
        serializer.serialize("t", "c", LocalTime.of(14, 30, 0)) shouldBe SerializedValue.Text("14:30:00")
    }

    test("LocalDateTime → ISO 8601") {
        serializer.serialize("t", "c", LocalDateTime.of(2024, 1, 15, 14, 30, 0)) shouldBe
            SerializedValue.Text("2024-01-15T14:30:00")
    }

    test("OffsetDateTime → ISO 8601 with offset") {
        val odt = OffsetDateTime.of(2024, 1, 15, 14, 30, 0, 0, ZoneOffset.ofHours(2))
        serializer.serialize("t", "c", odt) shouldBe SerializedValue.Text("2024-01-15T14:30:00+02:00")
    }

    // 0.8.0 Phase E (docs/ImpPlan-0.8.0-E.md §4.2 / §8 R3):
    // ZonedDateTime wird offsetbasiert serialisiert; die ZoneId ist in 0.8.0
    // nicht Teil des garantierten Vertrags — der Offset bleibt aber erhalten.
    test("ZonedDateTime → ISO 8601 mit Offset, ZoneId-Region entfaellt (Phase E §4.2)") {
        val zdt = ZonedDateTime.of(
            LocalDateTime.of(2024, 1, 15, 14, 30, 0),
            ZoneId.of("Europe/Berlin"),
        )
        serializer.serialize("t", "c", zdt) shouldBe
            SerializedValue.Text("2024-01-15T14:30:00+01:00")
    }

    test("LocalDateTime bleibt ohne Offset (Phase E §4.3)") {
        val ldt = LocalDateTime.of(2024, 1, 15, 14, 30, 0)
        val result = serializer.serialize("t", "c", ldt) as SerializedValue.Text
        // Keine stille Zonierung zu UTC oder JVM-Lokalzeit.
        result.value shouldBe "2024-01-15T14:30:00"
    }

    test("Instant → ISO 8601 UTC") {
        val inst = Instant.parse("2024-01-15T14:30:00Z")
        serializer.serialize("t", "c", inst) shouldBe SerializedValue.Text("2024-01-15T14:30:00Z")
    }

    test("UUID → String") {
        val uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        serializer.serialize("t", "c", uuid) shouldBe SerializedValue.Text("550e8400-e29b-41d4-a716-446655440000")
    }

    test("byte[] → Base64 (RFC 4648)") {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val expected = Base64.getEncoder().encodeToString(bytes)
        serializer.serialize("t", "c", bytes) shouldBe SerializedValue.Text(expected)
    }

    test("byte[] empty → empty Base64 string") {
        serializer.serialize("t", "c", ByteArray(0)) shouldBe SerializedValue.Text("")
    }

    // ─── W201 / W202 fallback ────────────────────────────────────

    test("Unknown class → toString() + W202 warning") {
        val warnings = mutableListOf<ValueSerializer.Warning>()
        val ser = ValueSerializer(warningSink = { warnings += it })
        val custom = object {
            override fun toString() = "custom!"
        }

        val result = ser.serialize("users", "weird_col", custom)
        result shouldBe SerializedValue.Text("custom!")
        warnings.size shouldBe 1
        warnings.single().code shouldBe "W202"
        warnings.single().table shouldBe "users"
        warnings.single().column shouldBe "weird_col"
        warnings.single().javaClass shouldContain "ValueSerializerTest"
    }

    test("Without warningSink, unknown class still serializes via toString()") {
        val ser = ValueSerializer()
        val custom = object { override fun toString() = "x" }
        ser.serialize("t", "c", custom) shouldBe SerializedValue.Text("x")
    }

    test("NaN double → string + W202 warning") {
        val warnings = mutableListOf<ValueSerializer.Warning>()
        val ser = ValueSerializer(warningSink = { warnings += it })
        val result = ser.serialize("t", "c", Double.NaN)
        result shouldBe SerializedValue.Text("NaN")
        warnings.size shouldBe 1
        warnings.single().code shouldBe "W202"
        warnings.single().message shouldContain "NaN"
    }

    test("Infinity double → string + W202 warning") {
        val warnings = mutableListOf<ValueSerializer.Warning>()
        val ser = ValueSerializer(warningSink = { warnings += it })
        ser.serialize("t", "c", Double.POSITIVE_INFINITY) shouldBe SerializedValue.Text("Infinity")
        warnings.size shouldBe 1
        warnings.single().code shouldBe "W202"
    }

    test("W202 deduplication: same (table, column, class) only warns once") {
        val warnings = mutableListOf<ValueSerializer.Warning>()
        val ser = ValueSerializer(warningSink = { warnings += it })
        val custom = object { override fun toString() = "x" }
        ser.serialize("t", "c", custom)
        ser.serialize("t", "c", custom)
        ser.serialize("t", "c", custom)
        warnings.size shouldBe 1
    }

    test("W202 separate keys: different columns produce separate warnings") {
        val warnings = mutableListOf<ValueSerializer.Warning>()
        val ser = ValueSerializer(warningSink = { warnings += it })
        val custom = object { override fun toString() = "x" }
        ser.serialize("t", "c1", custom)
        ser.serialize("t", "c2", custom)
        warnings.size shouldBe 2
        warnings.map { it.column }.toSet() shouldBe setOf("c1", "c2")
    }

    test("PGobject (via class name) calls getValue()") {
        // Wir simulieren PGobject mit einer Klasse die genau diesen FQN hat —
        // das geht in JVM nicht direkt; wir testen die generelle Reflection-Logik
        // mit einer Stub-Klasse mit getValue() Methode.
        // Der echte PGobject-Pfad wird im PostgreSQL-Integration-Test verifiziert.
        // Hier prüfen wir nur, dass unbekannte Klassen mit toString() landen.
        val stubPgObject = object {
            @Suppress("unused")
            fun getValue(): String = "json:{}"
            override fun toString() = "PGobject(json:{})"
        }
        // Stub hat nicht den FQN org.postgresql.util.PGobject — fällt auf W202
        val ser = ValueSerializer()
        ser.serialize("t", "c", stubPgObject) shouldBe SerializedValue.Text("PGobject(json:{})")
    }

    test("java.sql.Blob → Base64") {
        val blob = StubBlob(byteArrayOf(1, 2, 3))
        serializer.serialize("t", "c", blob) shouldBe SerializedValue.Text(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
    }

    test("java.sql.Clob → String content") {
        val clob = StubClob("hello world")
        serializer.serialize("t", "c", clob) shouldBe SerializedValue.Text("hello world")
    }

    test("F29: java.sql.Array → recursive Sequence (no warning on success)") {
        val warnings = mutableListOf<ValueSerializer.Warning>()
        val ser = ValueSerializer(warningSink = { warnings += it })
        val array = StubSqlArray(arrayOf<Any?>(1, 2, "x"))
        val result = ser.serialize("t", "c", array) as SerializedValue.Sequence
        result.elements shouldBe listOf(
            SerializedValue.Integer(1),
            SerializedValue.Integer(2),
            SerializedValue.Text("x"),
        )
        // §6.4.1: erfolgreiche Array-Enumeration soll *keine* W201 produzieren —
        // die formatspezifische Warnung emittiert der CsvChunkWriter selbst.
        warnings.shouldBeEmpty()
    }

    test("F29: java.sql.Array with primitive long[] → Sequence of Integer") {
        val ser = ValueSerializer()
        val array = StubSqlArray(longArrayOf(10L, 20L, 30L))
        val result = ser.serialize("t", "c", array) as SerializedValue.Sequence
        result.elements shouldBe listOf(
            SerializedValue.Integer(10),
            SerializedValue.Integer(20),
            SerializedValue.Integer(30),
        )
    }

    test("F29: java.sql.Array with byte[] payload → Base64 Text (binary fallback)") {
        val ser = ValueSerializer()
        val array = StubSqlArray(byteArrayOf(0x01, 0x02, 0x03))
        val result = ser.serialize("t", "c", array) as SerializedValue.Text
        result.value shouldBe java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x01, 0x02, 0x03))
    }

    test("java.sql.Struct → toString + W201") {
        val warnings = mutableListOf<ValueSerializer.Warning>()
        val ser = ValueSerializer(warningSink = { warnings += it })
        val struct = StubStruct("ROW(1,2)")
        ser.serialize("t", "c", struct) shouldBe SerializedValue.Text("ROW(1,2)")
        warnings.size shouldBe 1
        warnings.single().code shouldBe "W201"
    }
})

// ─── Stubs für JDBC LOB/Array/Struct (verhindern Connection-Pflicht) ───

private class StubBlob(private val bytes: ByteArray) : java.sql.Blob {
    override fun length() = bytes.size.toLong()
    override fun getBytes(pos: Long, length: Int) = bytes.copyOfRange(pos.toInt() - 1, pos.toInt() - 1 + length)
    override fun getBinaryStream() = bytes.inputStream()
    override fun getBinaryStream(pos: Long, length: Long) = bytes.copyOfRange(pos.toInt() - 1, pos.toInt() - 1 + length.toInt()).inputStream()
    override fun position(pattern: ByteArray, start: Long) = -1L
    override fun position(pattern: java.sql.Blob, start: Long) = -1L
    override fun setBytes(pos: Long, bytes: ByteArray) = throw UnsupportedOperationException()
    override fun setBytes(pos: Long, bytes: ByteArray, offset: Int, len: Int) = throw UnsupportedOperationException()
    override fun setBinaryStream(pos: Long) = throw UnsupportedOperationException()
    override fun truncate(len: Long) = Unit
    override fun free() = Unit
}

private class StubClob(private val content: String) : java.sql.Clob {
    override fun length() = content.length.toLong()
    override fun getSubString(pos: Long, length: Int) = content.substring(pos.toInt() - 1, pos.toInt() - 1 + length)
    override fun getCharacterStream() = content.reader()
    override fun getCharacterStream(pos: Long, length: Long) = content.substring(pos.toInt() - 1, pos.toInt() - 1 + length.toInt()).reader()
    override fun getAsciiStream() = content.byteInputStream(Charsets.US_ASCII)
    override fun position(searchstr: String, start: Long) = -1L
    override fun position(searchstr: java.sql.Clob, start: Long) = -1L
    override fun setString(pos: Long, str: String) = 0
    override fun setString(pos: Long, str: String, offset: Int, len: Int) = 0
    override fun setAsciiStream(pos: Long) = throw UnsupportedOperationException()
    override fun setCharacterStream(pos: Long) = throw UnsupportedOperationException()
    override fun truncate(len: Long) = Unit
    override fun free() = Unit
}

private class StubSqlArray(private val payload: Any) : java.sql.Array {
    override fun getBaseTypeName() = "VARCHAR"
    override fun getBaseType() = java.sql.Types.VARCHAR
    override fun getArray(): Any = payload
    override fun getArray(map: MutableMap<String, Class<*>>?) = payload
    override fun getArray(index: Long, count: Int) = payload
    override fun getArray(index: Long, count: Int, map: MutableMap<String, Class<*>>?) = payload
    override fun getResultSet() = throw UnsupportedOperationException()
    override fun getResultSet(map: MutableMap<String, Class<*>>?) = throw UnsupportedOperationException()
    override fun getResultSet(index: Long, count: Int) = throw UnsupportedOperationException()
    override fun getResultSet(index: Long, count: Int, map: MutableMap<String, Class<*>>?) = throw UnsupportedOperationException()
    override fun free() = Unit
}

private class StubStruct(private val text: String) : java.sql.Struct {
    override fun getSQLTypeName() = "ROW"
    override fun getAttributes() = arrayOf<Any?>()
    override fun getAttributes(map: MutableMap<String, Class<*>>?) = arrayOf<Any?>()
    override fun toString() = text
}
