package dev.dmigrate.core.diff

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigInteger

/**
 * Ein `default`, den Datei und Datenbank gleich ausdruecken, ist kein Unterschied (Issue #54):
 * Zahlen numerisch, `false`/`true` als `0`/`1` dort, wo das Ziel `boolean` auf `integer` faltet.
 */
class ColumnDefaultProjectionTest : FunSpec({

    fun num(n: Number) = DefaultValue.NumberLiteral(n)

    // Wie SQLite: boolean liegt als INTEGER ab.
    val sqliteLike = TargetProjection(type = { if (it == NeutralType.BooleanType) NeutralType.Integer else it })
    val keepsBoolean = TargetProjection(type = { it })

    fun schema(type: NeutralType, default: DefaultValue?) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf(
            "t" to TableDefinition(columns = mapOf("c" to ColumnDefinition(type, required = true, default = default))),
        ),
    )

    fun differs(
        a: Pair<NeutralType, DefaultValue?>,
        b: Pair<NeutralType, DefaultValue?>,
        projection: TargetProjection? = null,
    ): Boolean = !SchemaComparator(projection).compare(
        schema(a.first, a.second), schema(b.first, b.second),
    ).isEmpty()

    context("Zahlenliterale") {
        val sameNumbers = listOf(
            num(0) to num(0L),
            num(5) to num(5L),
            num(-1) to num(-1L),
            num(1) to num(1.0),
            num(0) to num(0.0),
            num(2.0) to num(2),
            num(1000) to num(1e3),
            num(BigInteger.valueOf(5_000_000_000L)) to num(5_000_000_000L),
        )
        for ((a, b) in sameNumbers) {
            test("${a.value.javaClass.simpleName}(${a.value}) = ${b.value.javaClass.simpleName}(${b.value})") {
                differs(NeutralType.Integer to a, NeutralType.Integer to b) shouldBe false
                differs(NeutralType.Integer to b, NeutralType.Integer to a, sqliteLike) shouldBe false
            }
        }

        test("verschiedene Zahlen bleiben verschieden") {
            differs(NeutralType.Integer to num(0), NeutralType.Integer to num(5L)) shouldBe true
            differs(NeutralType.Float() to num(1.5), NeutralType.Float() to num(2.5)) shouldBe true
            differs(NeutralType.Float() to num(1.5), NeutralType.Float() to num(1)) shouldBe true
        }

        test("Default hinzugefuegt und entfernt bleibt ein Unterschied") {
            differs(NeutralType.Integer to num(0), NeutralType.Integer to null) shouldBe true
            differs(NeutralType.Integer to null, NeutralType.Integer to num(0L)) shouldBe true
        }

        test("Zahl gegen Text bleibt ein Unterschied") {
            differs(
                NeutralType.Integer to num(0), NeutralType.Integer to DefaultValue.StringLiteral("0"),
            ) shouldBe true
        }
    }

    context("Wahrheitswert gegen 0/1") {
        val boolean = NeutralType.BooleanType
        val integer = NeutralType.Integer
        fun bool(b: Boolean) = DefaultValue.BooleanLiteral(b)

        test("false/true gegen 0/1: gleich, wenn das Ziel boolean auf integer faltet") {
            differs(boolean to bool(false), integer to num(0L), sqliteLike) shouldBe false
            differs(boolean to bool(true), integer to num(1L), sqliteLike) shouldBe false
            differs(integer to num(1L), boolean to bool(true), sqliteLike) shouldBe false
            // gleicher Typ auf beiden Seiten: nur der Default entscheidet
            differs(boolean to bool(false), boolean to num(0L), sqliteLike) shouldBe false
        }

        test("false gegen 1 bleibt ein Unterschied") {
            differs(boolean to bool(false), integer to num(1L), sqliteLike) shouldBe true
            differs(boolean to bool(true), integer to num(0L), sqliteLike) shouldBe true
        }

        test("ohne Projektion (schema compare) und bei einem Ziel, das boolean behaelt, ein Unterschied") {
            differs(boolean to bool(false), boolean to num(0L)) shouldBe true
            differs(boolean to bool(false), boolean to num(0L), keepsBoolean) shouldBe true
        }

        test("false gegen true an boolean bleibt ein Unterschied") {
            differs(boolean to bool(false), boolean to bool(true), sqliteLike) shouldBe true
        }

        test("project faltet nur BooleanLiteral an einer boolean-Spalte") {
            val canon = sqliteLike.type
            ColumnDefaultProjection.project(boolean, bool(true), canon) shouldBe num(1)
            ColumnDefaultProjection.project(integer, bool(true), canon) shouldBe bool(true)
            ColumnDefaultProjection.project(boolean, num(1), canon) shouldBe num(1)
            ColumnDefaultProjection.project(boolean, null, canon) shouldBe null
        }
    }

    context("Fingerabdruck folgt dem Vergleich") {
        fun fp(type: NeutralType, default: DefaultValue?, canon: (NeutralType) -> NeutralType = { it }) =
            MigrationFingerprint.compute(schema(type, default), canonicalizeType = canon)

        test("dieselbe Zahl hasht gleich, gleich welcher Zahlentyp") {
            fp(NeutralType.Integer, num(5)) shouldBe fp(NeutralType.Integer, num(5.0))
            fp(NeutralType.Float(), num(1)) shouldBe fp(NeutralType.Float(), num(1.0))
            fp(NeutralType.Decimal(10, 2), num(2.0)) shouldBe fp(NeutralType.Decimal(10, 2), num(2))
            fp(NeutralType.Decimal(10, 2), num(1e3)) shouldBe fp(NeutralType.Decimal(10, 2), num(1000L))
        }

        test("eine andere Zahl hasht anders") {
            (fp(NeutralType.Integer, num(0)) == fp(NeutralType.Integer, num(5))) shouldBe false
            (fp(NeutralType.Float(), num(1.5)) == fp(NeutralType.Float(), num(2.5))) shouldBe false
        }

        test("boolean-Default hasht auf SQLite wie 0/1 mit dem Typ integer, sonst anders") {
            val canon = sqliteLike.type
            // Gleicher Abdruck nur, wenn auch der Typ gleich ist: der Typ faltet unter canon auf integer.
            fp(NeutralType.BooleanType, DefaultValue.BooleanLiteral(false), canon) shouldBe
                fp(NeutralType.Integer, num(0L), canon)
            (fp(NeutralType.BooleanType, DefaultValue.BooleanLiteral(false)) ==
                fp(NeutralType.Integer, num(0L))) shouldBe false
        }
    }

    context("canonicalNumber und Gleichheit an den Raendern") {
        test("kanonische Schreibweise ohne Exponent und nachlaufende Nullen") {
            ColumnDefaultProjection.canonicalNumber(2.0) shouldBe "2"
            ColumnDefaultProjection.canonicalNumber(1e3) shouldBe "1000"
            ColumnDefaultProjection.canonicalNumber(-0.0) shouldBe "0"
            ColumnDefaultProjection.canonicalNumber(1.50) shouldBe "1.5"
            ColumnDefaultProjection.canonicalNumber(1.0E20) shouldBe "100000000000000000000"
        }

        test("NaN und Unendlich fallen auf den Text zurueck und sind nur sich selbst gleich") {
            ColumnDefaultProjection.canonicalNumber(Double.NaN) shouldBe "NaN"
            ColumnDefaultProjection.canonicalNumber(Double.POSITIVE_INFINITY) shouldBe "Infinity"
            ColumnDefaultProjection.same(num(Double.NaN), num(0)) shouldBe false
            ColumnDefaultProjection.same(num(Double.POSITIVE_INFINITY), num(Double.NEGATIVE_INFINITY)) shouldBe false
        }

        test("eine Ganzzahl ueber 2^53 wird nicht mit der gerundeten Kommazahl gleichgesetzt") {
            ColumnDefaultProjection.same(num(9007199254740993L), num(9007199254740992.0)) shouldBe false
            ColumnDefaultProjection.same(num(9007199254740992L), num(9007199254740992.0)) shouldBe true
        }

        test("0.1 + 0.2 ist nicht 0.3") {
            ColumnDefaultProjection.same(num(0.1 + 0.2), num(0.3)) shouldBe false
        }
    }
})
