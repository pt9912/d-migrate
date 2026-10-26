package dev.dmigrate.core.seed

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import kotlin.random.Random

/**
 * `data seed` mit einer Domain-Spalte (Issue #68): Werte des Basistyps, die den CHECK der Domain erfuellen; ein
 * CHECK, den der Seed nicht versteht, ist eine Meldung statt `NULL` oder eines Werts, an dem die Datenbank scheitert.
 */
class DomainColumnSeedTest : FunSpec({

    fun domain(base: String, check: String? = null, precision: Int? = null, scale: Int? = null) =
        CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base, check = check, precision = precision, scale = scale)

    fun rows(
        customTypes: Map<String, CustomTypeDefinition>,
        required: Boolean = false,
        count: Int = 200,
        rules: SeedRuleSet? = null,
    ): List<Any?> {
        val schema = SchemaDefinition(
            name = "t", version = "1", customTypes = customTypes,
            tables = mapOf(
                "t" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(), required = true, unique = true),
                        "d" to ColumnDefinition(NeutralType.Enum(refType = "dd"), required = required),
                    ),
                ),
            ),
        )
        return TableRowSeeder(Random(7), SeedLocale.EN, rules).seedAll(schema, count).getValue("t").map { it["d"] }
    }

    test("integer-Domain ohne CHECK erzeugt Zahlen, nicht NULL") {
        val values = rows(mapOf("dd" to domain("integer")))
        values shouldHaveSize 200
        values.all { it is Long } shouldBe true
    }

    test("CHECK VALUE >= 0 und BETWEEN: jeder Wert erfuellt ihn") {
        rows(mapOf("dd" to domain("integer", "VALUE >= 0"))).all { (it as Long) >= 0 } shouldBe true
        rows(mapOf("dd" to domain("integer", "VALUE BETWEEN 1 AND 10"))).all { (it as Long) in 1..10 } shouldBe true
        rows(mapOf("dd" to domain("integer", "(VALUE >= 1) AND (VALUE <= 10)"))).all { (it as Long) in 1..10 } shouldBe true
        rows(mapOf("dd" to domain("integer", "VALUE > 5 AND VALUE < 8"))).all { (it as Long) in 6..7 } shouldBe true
        rows(mapOf("dd" to domain("biginteger", "VALUE >= 2000000000"))).all { (it as Long) >= 2_000_000_000L } shouldBe true
        rows(mapOf("dd" to domain("smallint", "VALUE >= 0"))).all { (it as Long) in 0..32767 } shouldBe true
    }

    test("decimal-Domain mit CHECK: Werte im Bereich, mit der Skala der Domain") {
        val values = rows(mapOf("dd" to domain("decimal", "VALUE >= 0", precision = 10, scale = 2)))
        values.all { it is BigDecimal && it >= BigDecimal.ZERO && it.scale() == 2 } shouldBe true
    }

    test("Schranken nahe Long.MIN/MAX laufen nicht ueber: Werte im Bereich oder Meldung") {
        val high = 9_223_372_036_854_775_800L
        fun big(check: String) = rows(mapOf("dd" to domain("biginteger", check))).map { it as Long }
        big("VALUE >= $high").all { it >= high } shouldBe true
        big("VALUE <= -$high").all { it <= -high } shouldBe true
        shouldThrow<SeedPreflightException> { rows(mapOf("dd" to domain("integer", "VALUE > 3000000000"))) }
    }

    test("numeric(2,2) erzeugt nur Werte, die die Spalte annimmt (|Wert| < 1); ein Bereich ausserhalb ist unerfuellbar") {
        val values = rows(mapOf("dd" to domain("decimal", precision = 2, scale = 2)))
        values.all { it is BigDecimal && it.abs() < BigDecimal.ONE && it.scale() == 2 } shouldBe true
        val bounded = rows(mapOf("dd" to domain("decimal", "VALUE >= 0", precision = 2, scale = 2)))
        bounded.all { (it as BigDecimal) >= BigDecimal.ZERO && it < BigDecimal.ONE } shouldBe true
        val outside = domain("decimal", "VALUE >= 5", precision = 2, scale = 2)
        shouldThrow<SeedPreflightException> { rows(mapOf("dd" to outside)) }
    }

    test("Domain ueber real, int[] und INTEGER[] (handgeschrieben): der Seed loest sie auf") {
        rows(mapOf("dd" to domain("real", "VALUE >= 0"))).all { it is Double && it >= 0.0 } shouldBe true
        for (base in listOf("int[]", "INTEGER[]", "int4[]")) {
            withClue(base) { rows(mapOf("dd" to domain(base)), count = 5).all { it is List<*> } shouldBe true }
        }
    }

    test("text-Domain: VALUE <> '' und length(VALUE) > 2") {
        rows(mapOf("dd" to domain("text", "VALUE <> ''"))).all { (it as String).isNotEmpty() } shouldBe true
        rows(mapOf("dd" to domain("text", "length(VALUE) > 2"))).all { (it as String).length > 2 } shouldBe true
    }

    test("Wertelisten-CHECK (IN und die Katalogform ANY (ARRAY[...])): nur Werte der Liste") {
        val list = setOf("a", "b")
        rows(mapOf("dd" to domain("text", "VALUE IN ('a', 'b')"))).all { it in list } shouldBe true
        rows(mapOf("dd" to domain("text", "VALUE = ANY (ARRAY['a'::text, 'b'::text])"))).all { it in list } shouldBe true
        rows(mapOf("dd" to domain("integer", "VALUE IN (1, 2)"))).all { it == 1L || it == 2L } shouldBe true
    }

    test("CHECK, den der Seed nicht versteht: Meldung, auch bei nullbarer Spalte (kein stilles NULL)") {
        for (required in listOf(true, false)) {
            val e = shouldThrow<SeedPreflightException> {
                rows(mapOf("dd" to domain("text", "VALUE ~ '^[A-Z]+$'")), required = required)
            }
            e.message!! shouldContain "versteht den CHECK der Domain 'dd'"
            e.message!! shouldContain "--rules"
        }
    }

    test("IN/ANY mit mehr als einem Literal je Element oder einem OR-Rest ist kein Wertevorrat: Meldung") {
        val forms = listOf(
            "VALUE IN ('a') OR VALUE IN ('b')",
            "VALUE = ANY (ARRAY['a'::text]) OR VALUE = ANY (ARRAY['b'::text])",
            "VALUE IN ('a' || 'b')",
        )
        for (check in forms) {
            withClue(check) {
                shouldThrow<SeedPreflightException> { rows(mapOf("dd" to domain("text", check))) }
                    .message!! shouldContain "versteht den CHECK"
            }
        }
    }

    test("unerfuellbarer CHECK: Meldung") {
        shouldThrow<SeedPreflightException> { rows(mapOf("dd" to domain("integer", "VALUE > 5 AND VALUE < 3"))) }
            .message!! shouldContain "findet keinen Wert"
    }

    test("Domain ueber einer Domain: Meldung statt stillem NULL") {
        shouldThrow<SeedPreflightException> {
            rows(mapOf("d1" to domain("integer"), "dd" to domain("d1")))
        }.message!! shouldContain "Basistyp"
    }

    test("eine Enum-Spalte ohne values bleibt wie bisher: nullbar -> NULL") {
        val values = rows(mapOf("dd" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a"))))
        withClue("Enum mit ref_type erzeugt weiter keinen Wert (UnsupportedSeedType -> NULL)") {
            values.all { it == null } shouldBe true
        }
    }

    test("Preflight: eine nicht erzeugbare Domain-Spalte in Tabelle 3 bricht ab, bevor Tabelle 1 geschrieben wird") {
        fun table(column: ColumnDefinition?, fk: String? = null) = TableDefinition(
            columns = buildMap {
                put("id", ColumnDefinition(NeutralType.Identifier(), required = true, unique = true))
                if (fk != null) {
                    put(
                        "p_id",
                        ColumnDefinition(
                            NeutralType.Integer, required = true,
                            references = dev.dmigrate.core.model.ReferenceDefinition(table = fk, column = "id"),
                        ),
                    )
                }
                if (column != null) put("d", column)
            },
        )
        val schema = SchemaDefinition(
            name = "t", version = "1",
            customTypes = mapOf("dd" to domain("text", "VALUE ~ '^[A-Z]+$'")),
            tables = mapOf(
                "t1" to table(null),
                "t2" to table(null, fk = "t1"),
                "t3" to table(ColumnDefinition(NeutralType.Enum(refType = "dd")), fk = "t2"),
            ),
        )
        val written = mutableListOf<String>()
        shouldThrow<SeedPreflightException> {
            TableRowSeeder(Random(1), SeedLocale.EN).seedEach(schema, 3) { name, _ -> written += name }
        }
        written shouldBe emptyList()
    }

    test("deterministisch bei gleichem Seed") {
        val types = mapOf("dd" to domain("integer", "VALUE BETWEEN 1 AND 10"))
        rows(types) shouldBe rows(types)
    }
})
