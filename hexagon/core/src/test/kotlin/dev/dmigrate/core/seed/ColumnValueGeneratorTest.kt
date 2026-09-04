package dev.dmigrate.core.seed

import dev.dmigrate.core.model.NeutralType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.random.Random

class ColumnValueGeneratorTest : FunSpec({

    fun generatorFor(seed: Long, locale: SeedLocale = SeedLocale.EN) =
        ColumnValueGenerator(Random(seed), locale)

    test("determinism: same seed and same call sequence produce the same values") {
        val types = listOf(
            NeutralType.Integer,
            NeutralType.Text(maxLength = 20),
            NeutralType.Uuid,
            NeutralType.Decimal(10, 2),
        )
        val first = generatorFor(42).let { g -> types.map { g.generate(it) } }
        val second = generatorFor(42).let { g -> types.map { g.generate(it) } }
        first shouldBe second
    }

    test("different seeds produce different values (overwhelmingly likely)") {
        val a = generatorFor(1).generate(NeutralType.Text(maxLength = 50))
        val b = generatorFor(2).generate(NeutralType.Text(maxLength = 50))
        (a == b) shouldBe false
    }

    test("Identifier is a positive Long") {
        val value = generatorFor(1).generate(NeutralType.Identifier()) as Long
        (value in 1L..999_999L) shouldBe true
    }

    test("SmallInt stays within 16-bit range") {
        val value = generatorFor(1).generate(NeutralType.SmallInt) as Long
        (value in -32_768L..32_767L) shouldBe true
    }

    test("Text respects maxLength") {
        val value = generatorFor(7).generate(NeutralType.Text(maxLength = 5)) as String
        (value.length <= 5) shouldBe true
    }

    test("Char produces exact length") {
        val value = generatorFor(1).generate(NeutralType.Char(6)) as String
        value.length shouldBe 6
    }

    test("Decimal respects scale") {
        val value = generatorFor(1).generate(NeutralType.Decimal(10, 3)) as BigDecimal
        value.scale() shouldBe 3
    }

    test("BooleanType, Date, Time, DateTime produce the expected Java runtime types") {
        val g = generatorFor(1)
        g.generate(NeutralType.BooleanType).shouldBeInstanceOf<Boolean>()
        g.generate(NeutralType.Date).shouldBeInstanceOf<LocalDate>()
        g.generate(NeutralType.Time).shouldBeInstanceOf<LocalTime>()
        g.generate(NeutralType.DateTime(timezone = false)).shouldBeInstanceOf<LocalDateTime>()
        g.generate(NeutralType.DateTime(timezone = true)).shouldBeInstanceOf<OffsetDateTime>()
    }

    test("Uuid produces java.util.UUID (AE-9)") {
        generatorFor(1).generate(NeutralType.Uuid).shouldBeInstanceOf<UUID>()
    }

    test("Binary produces a ByteArray of fixed length") {
        val value = generatorFor(1).generate(NeutralType.Binary) as ByteArray
        value.size shouldBe 16
    }

    test("Email contains exactly one @ and a locale domain") {
        val value = generatorFor(1).generate(NeutralType.Email) as String
        value.count { it == '@' } shouldBe 1
        SeedLocale.EN.emailDomains.any { value.endsWith(it) } shouldBe true
    }

    test("Enum picks only from the declared values") {
        val values = listOf("draft", "active", "archived")
        val g = generatorFor(1)
        repeat(20) {
            val picked = g.generate(NeutralType.Enum(values = values))
            (picked in values) shouldBe true
        }
    }

    test("Enum without values throws UnsupportedSeedTypeException") {
        shouldThrow<UnsupportedSeedTypeException> {
            generatorFor(1).generate(NeutralType.Enum(values = null, refType = "status"))
        }
    }

    test("Geometry throws UnsupportedSeedTypeException (AE-10)") {
        shouldThrow<UnsupportedSeedTypeException> {
            generatorFor(1).generate(NeutralType.Geometry())
        }
    }

    test("FullText throws UnsupportedSeedTypeException (AE-10)") {
        shouldThrow<UnsupportedSeedTypeException> {
            generatorFor(1).generate(NeutralType.FullText)
        }
    }

    test("Array generates a list of the mapped element type (integer)") {
        val value = generatorFor(1).generate(NeutralType.Array(elementType = "integer")) as List<*>
        value.isNotEmpty() shouldBe true
        value.all { it is Long } shouldBe true
    }

    test("Array generates a list of the mapped element type (text)") {
        val value = generatorFor(1).generate(NeutralType.Array(elementType = "text")) as List<*>
        value.isNotEmpty() shouldBe true
        value.all { it is String } shouldBe true
    }

    test("Array falls back to text for an unmapped element type") {
        val value = generatorFor(1).generate(NeutralType.Array(elementType = "enum")) as List<*>
        value.all { it is String } shouldBe true
    }

    test("de locale produces different words than en locale") {
        val en = generatorFor(1, SeedLocale.EN).generate(NeutralType.Text(maxLength = 50)) as String
        val de = generatorFor(1, SeedLocale.DE).generate(NeutralType.Text(maxLength = 50)) as String
        (en == de) shouldBe false
    }

    test("SeedLocale.fromFlag resolves known flags and rejects unknown ones") {
        SeedLocale.fromFlag("en") shouldBe SeedLocale.EN
        SeedLocale.fromFlag("DE") shouldBe SeedLocale.DE
        SeedLocale.fromFlag("fr") shouldBe null
    }

    test("Json produces syntactically plausible JSON text") {
        val value = generatorFor(1).generate(NeutralType.Json) as String
        value shouldContain "\"seed\""
    }
})
