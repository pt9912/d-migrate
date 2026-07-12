package dev.dmigrate.format.verify

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.neutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * LN-009 / ADR 0029 / ADR 0030: Property-Based Test der Wert-Kanonik.
 *
 * Deckt über [neutralType] jeden der 21 NeutralType-Zweige ab und prüft
 * **Determinismus**: zwei unabhängige Codec-Instanzen liefern für denselben
 * logischen Wert byte-identische Kanonik (fängt versteckte Nichtdeterminismen
 * wie HashMap-Key-Reihenfolge in der JSON-Kanonik oder Default-Zeitzonen).
 */
class CanonicalValueCodecPropertyTest : FunSpec({

    test("Kanonik ist deterministisch über alle NeutralType-Zweige") {
        checkAll(Arb.neutralType()) { type ->
            val value = valueFor(type)
            val a = CanonicalValueCodec().canonicalize(value, type)
            val b = CanonicalValueCodec().canonicalize(value, type)
            a.toList() shouldBe b.toList()
        }
    }
})

/** Ein für den gegebenen [NeutralType] gültiger Repräsentant. */
private fun valueFor(type: NeutralType): Any = when (type) {
    is NeutralType.Text, NeutralType.Xml, NeutralType.Email, is NeutralType.Enum, NeutralType.FullText,
    is NeutralType.Char -> "sample value"
    NeutralType.Uuid -> UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    NeutralType.Integer, NeutralType.SmallInt, NeutralType.BigInteger, is NeutralType.Identifier -> 7
    is NeutralType.Decimal -> BigDecimal("1.50")
    is NeutralType.Float -> 1.5
    NeutralType.BooleanType -> true
    NeutralType.Date -> LocalDate.of(2020, 1, 2)
    NeutralType.Time -> LocalTime.of(1, 2, 3)
    is NeutralType.DateTime ->
        if (type.timezone) OffsetDateTime.of(2020, 1, 2, 12, 0, 0, 0, ZoneOffset.ofHours(2)) else LocalDateTime.of(2020, 1, 2, 12, 0, 0)
    NeutralType.Json -> """{"b":1,"a":2,"nested":{"y":1,"x":2}}"""
    is NeutralType.Array -> arrayOf<Any?>(1, 2, 3)
    NeutralType.Binary -> byteArrayOf(1, 2, 3, 4)
    is NeutralType.Geometry -> "0101000000000000000000F03F0000000000000040"
}
