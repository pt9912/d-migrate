package dev.dmigrate.format.data

import io.kotest.core.spec.style.FunSpec
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
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.sql.Date as SqlDate
import java.sql.Time as SqlTime

/**
 * Verifiziert die §6.4.1 Java-Klasse → SerializedValue Mapping-Tabelle aus
 * dem implementation-plan-0.3.0.md zeilenweise.
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

    test("BigInteger → PreciseNumber (string-encoded for precision)") {
        val big = BigInteger("123456789012345678901234567890")
        serializer.serialize("t", "c", big) shouldBe SerializedValue.PreciseNumber(big.toString())
    }

    test("BigDecimal → PreciseNumber (toPlainString for stability)") {
        val dec = BigDecimal("12345.6789")
        serializer.serialize("t", "c", dec) shouldBe SerializedValue.PreciseNumber("12345.6789")
    }

    test("BigDecimal preserves trailing zeros via toPlainString") {
        val dec = BigDecimal("100.00")
        serializer.serialize("t", "c", dec) shouldBe SerializedValue.PreciseNumber("100.00")
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

    // ─── W202 fallback ───────────────────────────────────────────

    test("Unknown class → toString() + W202 warning") {
        val warnings = mutableListOf<ValueSerializer.W202>()
        val ser = ValueSerializer(warningSink = { warnings += it })
        val custom = object {
            override fun toString() = "custom!"
        }

        val result = ser.serialize("users", "weird_col", custom)
        result shouldBe SerializedValue.Text("custom!")
        warnings.size shouldBe 1
        warnings.single().table shouldBe "users"
        warnings.single().column shouldBe "weird_col"
        warnings.single().javaClass shouldContain "ValueSerializerTest"
    }

    test("Without warningSink, unknown class still serializes via toString()") {
        val ser = ValueSerializer()
        val custom = object { override fun toString() = "x" }
        ser.serialize("t", "c", custom) shouldBe SerializedValue.Text("x")
    }
})
