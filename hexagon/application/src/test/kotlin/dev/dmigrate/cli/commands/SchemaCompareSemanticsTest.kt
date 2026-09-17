package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.ReverseMarkerNormalizer
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Die Reverse-Markierung in `schema compare` (Review Runde 3, M1): traegt
 * eine Seite sie, stehen in `name` und `version` Platzhalter — dann sind
 * beide kein Vergleichsgegenstand, und nach aussen dringt kein Platzhalter.
 */
class SchemaCompareSemanticsTest : FunSpec({

    val reverse = ReverseScopeCodec.REVERSE_VERSION
    val pgReverse = SchemaDefinition(name = ReverseScopeCodec.postgresName("shop", "public"), version = reverse)
    val myReverse = SchemaDefinition(name = ReverseScopeCodec.mysqlName("shop"), version = reverse)
    val authored = SchemaDefinition(name = "shop", version = "1.2.0")

    fun compare(source: SchemaDefinition, target: SchemaDefinition) =
        SchemaCompareSemantics.compare(SchemaCompareSemantics.side(source), SchemaCompareSemantics.side(target))

    test("a side knows whether it carried the marker, and loses it") {
        SchemaCompareSemantics.side(pgReverse).run {
            reverseGenerated shouldBe true
            sourceDialect shouldBe DatabaseDialect.POSTGRESQL
            schema.name shouldBe ReverseMarkerNormalizer.NORMALIZED_NAME
        }
        SchemaCompareSemantics.side(authored).run {
            reverseGenerated shouldBe false
            sourceDialect.shouldBeNull()
            schema shouldBe authored
        }
    }

    test("a marker with a dialect this version does not know is still a marker") {
        val unknown = SchemaDefinition(name = "${ReverseScopeCodec.PREFIX}db2:database=x", version = reverse)
        SchemaCompareSemantics.side(unknown).run {
            reverseGenerated shouldBe true
            sourceDialect.shouldBeNull()
        }
    }

    test("one reversed side: name and version are no subject of the comparison") {
        compare(pgReverse, authored).schemaMetadata.shouldBeNull()
        compare(authored, myReverse).schemaMetadata.shouldBeNull()
        compare(pgReverse, authored).isEmpty() shouldBe true
    }

    test("two reversed sides: no change either") {
        compare(pgReverse, myReverse).schemaMetadata.shouldBeNull()
    }

    test("two hand-written sides: the values of both sides, never a placeholder") {
        val metadata = compare(authored, authored.copy(name = "shop2", version = "2.0.0")).schemaMetadata
            .shouldNotBeNull()
        metadata.name shouldBe ValueChange("shop", "shop2")
        metadata.version shouldBe ValueChange("1.2.0", "2.0.0")
    }

    test("a hand-built side without a dialect but with the flag is treated as reversed") {
        val side = CompareSide(authored.copy(name = "x"), sourceDialect = null, reverseGenerated = true)
        SchemaCompareSemantics.compare(side, CompareSide(authored)).schemaMetadata.shouldBeNull()
    }

    test("a half marker is rejected") {
        shouldThrow<IllegalStateException> {
            SchemaCompareSemantics.side(SchemaDefinition(name = ReverseScopeCodec.mysqlName("shop"), version = "1"))
        }
    }
})
