package dev.dmigrate.format.verify

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.verify.ValueCanonicalizationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.sql.Date as SqlDate
import java.sql.Time as SqlTime

/**
 * LN-009 / ADR 0030: Wert-Kanonik je NeutralType + Cross-Dialekt-Äquivalenz
 * (projektions-bewusst — flattening-äquivalente Werte kollidieren).
 */
class CanonicalValueCodecTest : FunSpec({

    val codec = CanonicalValueCodec()
    fun str(value: Any, type: NeutralType) = String(codec.canonicalize(value, type), Charsets.UTF_8)
    fun same(a: Pair<Any, NeutralType>, b: Pair<Any, NeutralType>) =
        codec.canonicalize(a.first, a.second).toList() shouldBe codec.canonicalize(b.first, b.second).toList()

    context("String-Familie") {
        test("Text bleibt UTF-8 (inkl. Unicode/Emoji)") {
            str("héllo 🌍 日本", NeutralType.Text()) shouldBe "héllo 🌍 日本"
        }
        test("Char normalisiert trailing spaces (PG-Pad vs MySQL-Trim)") {
            str("abc  ", NeutralType.Char(5)) shouldBe "abc"
            same("abc" to NeutralType.Char(5), "abc   " to NeutralType.Char(5))
        }
        test("Enum/Xml/Email/FullText über Textform") {
            str("ACTIVE", NeutralType.Enum(listOf("ACTIVE"))) shouldBe "ACTIVE"
            str("<a/>", NeutralType.Xml) shouldBe "<a/>"
            str("a@b.de", NeutralType.Email) shouldBe "a@b.de"
            str("lexeme", NeutralType.FullText) shouldBe "lexeme"
        }
        test("Uuid: UUID-Objekt und Textform kollidieren (Lowercase)") {
            val u = UUID.fromString("550E8400-E29B-41D4-A716-446655440000")
            str(u, NeutralType.Uuid) shouldBe "550e8400-e29b-41d4-a716-446655440000"
            same(u to NeutralType.Uuid, "550e8400-e29b-41d4-a716-446655440000" to NeutralType.Uuid)
        }
    }

    context("Numerik + Boolean-Flattening") {
        test("Integer aus Int/Long/BigInteger/String identisch") {
            str(42, NeutralType.Integer) shouldBe "42"
            same(42 to NeutralType.Integer, 42L to NeutralType.Integer)
            same(42 to NeutralType.Integer, BigInteger.valueOf(42) to NeutralType.Integer)
            same(42 to NeutralType.Integer, "42" to NeutralType.Integer)
        }
        test("Boolean unter Integer-Projektion → 1/0 wie Integer (SQLite boolean→INTEGER)") {
            str(true, NeutralType.Integer) shouldBe "1"
            same(true to NeutralType.Integer, 1 to NeutralType.Integer)
            same(false to NeutralType.Integer, 0L to NeutralType.Integer)
        }
        test("BooleanType: Boolean und Int 0/1 kollidieren") {
            str(true, NeutralType.BooleanType) shouldBe "1"
            same(true to NeutralType.BooleanType, 1 to NeutralType.BooleanType)
            same(false to NeutralType.BooleanType, 0 to NeutralType.BooleanType)
        }
        test("Decimal strippt trailing zeros (1.50 == 1.5), -0 == 0") {
            str(BigDecimal("1.50"), NeutralType.Decimal(10, 2)) shouldBe "1.5"
            same(BigDecimal("1.50") to NeutralType.Decimal(10, 2), BigDecimal("1.5") to NeutralType.Decimal(10, 1))
            same(BigDecimal("600") to NeutralType.Decimal(10, 0), BigDecimal("6E2") to NeutralType.Decimal(10, 0))
            str(BigDecimal("-0.00"), NeutralType.Decimal(10, 2)) shouldBe "0"
        }
        test("Float: gleich-breite kürzeste Dezimale kollidiert Float/Double") {
            str(1.5f, NeutralType.Float()) shouldBe "1.5"
            same(1.5f to NeutralType.Float(), 1.5 to NeutralType.Float())
            str(Double.NaN, NeutralType.Float()) shouldBe "NaN"
            str(Double.POSITIVE_INFINITY, NeutralType.Float()) shouldBe "Infinity"
        }
    }

    context("Temporal") {
        test("Date aus SqlDate/LocalDate identisch") {
            str(LocalDate.of(2020, 1, 2), NeutralType.Date) shouldBe "2020-01-02"
            same(SqlDate.valueOf("2020-01-02") to NeutralType.Date, LocalDate.of(2020, 1, 2) to NeutralType.Date)
        }
        test("Time normalisiert") {
            str(LocalTime.of(10, 30, 0), NeutralType.Time) shouldBe "10:30:00"
            same(SqlTime.valueOf("10:30:00") to NeutralType.Time, LocalTime.of(10, 30) to NeutralType.Time)
        }
        test("DateTime ohne tz: local ISO") {
            str(LocalDateTime.of(2020, 1, 2, 10, 30, 0), NeutralType.DateTime(false)) shouldBe "2020-01-02T10:30:00"
            same(
                Timestamp.valueOf("2020-01-02 10:30:00") to NeutralType.DateTime(false),
                LocalDateTime.of(2020, 1, 2, 10, 30, 0) to NeutralType.DateTime(false),
            )
        }
        test("DateTime tz: auf UTC-Instant normalisiert (Offset kollabiert)") {
            val odt = OffsetDateTime.of(2020, 1, 2, 12, 0, 0, 0, ZoneOffset.ofHours(2))
            str(odt, NeutralType.DateTime(true)) shouldBe "2020-01-02T10:00:00Z"
            same(
                odt to NeutralType.DateTime(true),
                Instant.parse("2020-01-02T10:00:00Z") to NeutralType.DateTime(true),
            )
        }
    }

    context("Binary + Array") {
        test("Binary trägt rohe Bytes") {
            codec.canonicalize(byteArrayOf(1, 2, 3), NeutralType.Binary).toList() shouldBe listOf<Byte>(1, 2, 3)
        }
        test("Array rekursiv, längen-gerahmt; verschiedene Werte ≠") {
            val a = codec.canonicalize(arrayOf(1, 2, 3), NeutralType.Array("integer"))
            val b = codec.canonicalize(arrayOf(1, 2, 3), NeutralType.Array("integer"))
            a.toList() shouldBe b.toList()
            val c = codec.canonicalize(arrayOf(1, 2, 4), NeutralType.Array("integer"))
            a.toList() shouldNotBe c.toList()
        }
    }

    context("Fehlerpfade") {
        test("nicht-kanonisierbarer Integer-Wert wirft") {
            shouldThrow<ValueCanonicalizationException> {
                codec.canonicalize(Any(), NeutralType.Integer)
            }
        }
        test("nicht-kanonisierbare Geometrie wirft (SpatiaLite-BLOB-Grenze)") {
            shouldThrow<ValueCanonicalizationException> {
                codec.canonicalize(byteArrayOf(0x00, 0x11, 0x22), NeutralType.Geometry())
            }
        }
    }
})
