package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the Oracle canonicalisation (live composition `reverse(toSql(t))`)
 * gegen die Oracle-Speicherrealitaet. Das lebende Gegenstueck --
 * `OracleNeutralTypeCanonicalizerIntegrationTest` in `test/integration-oracle`
 * -- belegt dieselbe Tabelle gegen ein echtes Oracle, damit die Komposition
 * nicht still von dem abweicht, was der Reader tatsaechlich zurueckliest.
 */
class OracleNeutralTypeCanonicalizerTest : FunSpec({

    val canon = OracleNeutralTypeCanonicalizer

    // Kantentabelle: links der neutrale Typ, rechts das, was nach
    // generate → Oracle → reverse zurueckkommt.
    val expected = mapOf<NeutralType, NeutralType>(
        // Fixpunkte — Oracle traegt diese Typen eigenstaendig.
        NeutralType.Integer to NeutralType.Integer,
        NeutralType.SmallInt to NeutralType.SmallInt,
        NeutralType.BigInteger to NeutralType.BigInteger,
        NeutralType.BooleanType to NeutralType.BooleanType,
        NeutralType.Json to NeutralType.Json,
        NeutralType.Xml to NeutralType.Xml,
        NeutralType.Binary to NeutralType.Binary,
        NeutralType.DateTime() to NeutralType.DateTime(),
        NeutralType.DateTime(timezone = true) to NeutralType.DateTime(timezone = true),
        NeutralType.Float(FloatPrecision.SINGLE) to NeutralType.Float(FloatPrecision.SINGLE),
        NeutralType.Float(FloatPrecision.DOUBLE) to NeutralType.Float(FloatPrecision.DOUBLE),
        NeutralType.Decimal(10, 2) to NeutralType.Decimal(10, 2),
        NeutralType.Decimal(38, 0) to NeutralType.Decimal(38, 0),
        NeutralType.Text(maxLength = null) to NeutralType.Text(maxLength = null),
        NeutralType.Text(maxLength = 255) to NeutralType.Text(maxLength = 255),
        NeutralType.Text(maxLength = 4000) to NeutralType.Text(maxLength = 4000),
        NeutralType.Char(length = 10) to NeutralType.Char(length = 10),
        NeutralType.Char(length = 2000) to NeutralType.Char(length = 2000),

        // Abflachungen — die Spalte traegt die Unterscheidung nicht.
        // Identity (mit oder ohne autoIncrement) ist eine blanke NUMBER(9) --
        // OracleTypeMapping.mapIdentity (Slice 1) liefert NeutralType.Identifier
        // beim Reverse nie zurueck, ANDERS als bei PostgreSQL.
        NeutralType.Identifier(autoIncrement = true) to NeutralType.Integer,
        NeutralType.Identifier(autoIncrement = false) to NeutralType.Integer,
        // VARCHAR2/CHAR ueber der Schwelle -> CLOB (W145).
        NeutralType.Text(maxLength = 4001) to NeutralType.Text(maxLength = null),
        NeutralType.Char(length = 2001) to NeutralType.Text(maxLength = null),
        // DECIMAL kappt bei Praezision 38 (W148).
        NeutralType.Decimal(50, 4) to NeutralType.Decimal(38, 4),
        // NUMBER(p,0) faltet auf den Integer-Typ-Tier (Slice-1-Entscheidung,
        // dokumentiert in OracleTypeMapping) -- NUMBER(1,0) trifft Oracles
        // eigene Boolean-Konvention.
        NeutralType.Decimal(1, 0) to NeutralType.BooleanType,
        NeutralType.Decimal(3, 0) to NeutralType.SmallInt,
        NeutralType.Decimal(10, 0) to NeutralType.BigInteger,
        // Oracle DATE traegt immer eine Uhrzeit (W147) -- Reverse faltet auf
        // datetime, nicht auf date, um sie nicht stillschweigend zu verlieren.
        NeutralType.Date to NeutralType.DateTime(),
        // Kein natives Zeit-/UUID-Typ -- VARCHAR2(8)/VARCHAR2(36) (W146).
        NeutralType.Time to NeutralType.Text(maxLength = 8),
        NeutralType.Uuid to NeutralType.Text(maxLength = 36),
        // Kein natives Array -- JSON (W149), das seinerseits Fixpunkt ist.
        NeutralType.Array(elementType = "text") to NeutralType.Json,
        // Kein Volltext-Vektortyp -- CLOB (W132, geteilter Cross-Dialekt-Pool).
        NeutralType.FullText to NeutralType.Text(maxLength = null),
        NeutralType.Email to NeutralType.Text(maxLength = NeutralType.Email.MAX_LENGTH),
    )

    test("canonical projection matches the Oracle storage reality") {
        for ((type, canonical) in expected) {
            withClue(type) { canon.canonicalize(type) shouldBe canonical }
        }
    }

    test("enum folds onto the bounded VARCHAR2 the column helper renders") {
        // Der Spalten-Helfer rendert VARCHAR2(<laengster Wert>) + CHECK, nicht
        // VARCHAR2(4000) -- der Kanonisierer muss dieselbe Breite projizieren.
        canon.canonicalize(NeutralType.Enum(values = listOf("red", "green"))) shouldBe
            NeutralType.Text(maxLength = 5)
        canon.canonicalize(NeutralType.Enum(values = listOf(""))) shouldBe NeutralType.Text(maxLength = 1)
        // Ohne Werte faellt plainColumn auf toSql zurueck: VARCHAR2(4000) --
        // gebunden, nicht unbegrenzt (anders als MSSQLs NVARCHAR(MAX)).
        canon.canonicalize(NeutralType.Enum()) shouldBe NeutralType.Text(maxLength = 4000)
    }

    test("enum with an unresolved refType and no inline values falls through to the unbounded fallback") {
        // enumColumn kennt kein "unbekannt bleiben": weder ein refType, den das
        // Schema gar nicht fuehrt, noch einer, der auf einen Custom Type ohne
        // `values` zeigt (z. B. COMPOSITE), unterbricht die Kette -- beide
        // landen wie ein werteloser Enum auf plainColumn (VARCHAR2(4000)).
        canon.canonicalize(NeutralType.Enum(refType = "mood")) shouldBe NeutralType.Text(maxLength = 4000)
        val composite = mapOf("shape" to CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE, fields = emptyMap()))
        canon.canonicalize(NeutralType.Enum(refType = "shape"), composite) shouldBe NeutralType.Text(maxLength = 4000)
    }

    test("enum with a refType but inline values falls back to the inline values (matches enumColumn)") {
        // OracleColumnConstraintHelper.enumColumn: (customType?.values ?:
        // type.values) -- ohne aufloesbaren Custom Type rendert Oracle trotzdem
        // aus den inline-Werten, anders als schlicht identity zu bleiben.
        val both = NeutralType.Enum(values = listOf("a"), refType = "mood")
        canon.canonicalize(both) shouldBe NeutralType.Text(maxLength = 1)
    }

    test("enum with a refType resolves against a real ENUM custom type") {
        val customTypes = mapOf("mood" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("happy", "sad")))
        canon.canonicalize(NeutralType.Enum(refType = "mood"), customTypes) shouldBe NeutralType.Text(maxLength = 5)
    }

    test("enum with a refType to a DOMAIN always folds to CLOB, ignoring the domain's base type") {
        // Oracle loest heute keinen Domain-Basistyp auf (E053) -- unabhaengig
        // vom deklarierten baseType faellt es auf CLOB.
        val customTypes = mapOf(
            "status_domain" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "text"),
        )
        canon.canonicalize(NeutralType.Enum(refType = "status_domain"), customTypes) shouldBe
            NeutralType.Text(maxLength = null)
    }

    test("geometry stays identity — unreachable in practice (canGenerateSpatial=false)") {
        canon.canonicalize(NeutralType.Geometry(GeometryType.of("point"), srid = 4326)) shouldBe
            NeutralType.Geometry(GeometryType.of("point"), srid = 4326)
        canon.canonicalize(NeutralType.Geometry()) shouldBe NeutralType.Geometry()
    }

    test("projection is idempotent") {
        val all = expected.keys +
            NeutralType.Enum(values = listOf("red", "green")) +
            NeutralType.Enum(refType = "mood") +
            NeutralType.Geometry(GeometryType.of("point"), srid = 4326) +
            NeutralType.Geometry()
        for (type in all) {
            val once = canon.canonicalize(type)
            withClue(type) { canon.canonicalize(once) shouldBe once }
        }
    }
})
