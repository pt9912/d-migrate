package dev.dmigrate.format.verify

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.verify.ValueCanonicalizationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * LN-009 / ADR 0030: Geometrie-Kanonik über WKB. PostGIS-EWKB (Hex) und
 * MySQL-SRID-präfigiertes WKB derselben Geometrie kollidieren; Byte-Order ist
 * invariant; SRID=0-Mehrdeutigkeit wird über exakten Byte-Verbrauch aufgelöst.
 */
class CanonicalGeometryTest : FunSpec({

    val geom = CanonicalGeometry()
    val codec = CanonicalValueCodec(geometry = geom)
    fun hex(s: String) = ByteArray(s.length / 2) { ((digit(s[it * 2]) shl 4) or digit(s[it * 2 + 1])).toByte() }

    // POINT(1 2)
    val plainLe = "0101000000000000000000F03F0000000000000040"
    val plainBe = "00000000013FF00000000000004000000000000000"
    val pgEwkb4326 = "0101000020E6100000000000000000F03F0000000000000040"
    val mysql4326 = hex("E61000000101000000000000000000F03F0000000000000040")
    val mysql0 = hex("000000000101000000000000000000F03F0000000000000040")

    test("PostGIS-EWKB-Hex und MySQL-SRID-Präfix derselben Geometrie kollidieren") {
        geom.canonicalize(pgEwkb4326).toList() shouldBe geom.canonicalize(mysql4326).toList()
    }

    test("Byte-Order ist invariant (LE == BE)") {
        geom.canonicalize(plainLe).toList() shouldBe geom.canonicalize(plainBe).toList()
    }

    test("SRID=0-MySQL wird über exakten Byte-Verbrauch von Plain-WKB unterschieden") {
        geom.canonicalize(plainLe).toList() shouldBe geom.canonicalize(mysql0).toList()
    }

    test("verschiedene SRID → verschiedene Kanonik") {
        geom.canonicalize(pgEwkb4326).toList() shouldNotBe geom.canonicalize(plainLe).toList()
    }

    test("Codec-Geometry-Dispatch über Hex-String-Pfad (EWKB) kollidiert mit MySQL") {
        codec.canonicalize(pgEwkb4326, NeutralType.Geometry()).toList() shouldBe geom.canonicalize(mysql4326).toList()
    }

    test("SpatiaLite-artiger BLOB (kein Standard-WKB) wirft") {
        shouldThrow<ValueCanonicalizationException> {
            geom.canonicalize(hex("00010000006789"))
        }
    }
})

private fun digit(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> error("bad hex")
}
