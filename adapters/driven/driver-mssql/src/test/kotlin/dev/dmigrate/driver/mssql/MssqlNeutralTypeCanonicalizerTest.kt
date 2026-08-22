package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the MSSQL canonicalisation (live composition `reverse(toSql(t))`)
 * against the T-SQL storage reality. The live counterpart —
 * `MssqlNeutralTypeCanonicalizerIntegrationTest` in `test/integration-mssql`
 * — proves the same table against a real SQL Server, so the composition
 * cannot drift silently from what the reader reads back.
 */
class MssqlNeutralTypeCanonicalizerTest : FunSpec({

    val canon = MssqlNeutralTypeCanonicalizer

    // Kantentabelle: links der neutrale Typ, rechts das, was nach
    // generate → SQL Server → reverse zurueckkommt.
    val expected = mapOf<NeutralType, NeutralType>(
        // Fixpunkte — T-SQL traegt diese Typen eigenstaendig.
        NeutralType.Integer to NeutralType.Integer,
        NeutralType.SmallInt to NeutralType.SmallInt,
        NeutralType.BigInteger to NeutralType.BigInteger,
        NeutralType.BooleanType to NeutralType.BooleanType,
        NeutralType.Date to NeutralType.Date,
        NeutralType.Time to NeutralType.Time,
        NeutralType.Uuid to NeutralType.Uuid,
        NeutralType.Xml to NeutralType.Xml,
        NeutralType.Binary to NeutralType.Binary,
        NeutralType.DateTime() to NeutralType.DateTime(),
        NeutralType.DateTime(timezone = true) to NeutralType.DateTime(timezone = true),
        NeutralType.Float(FloatPrecision.SINGLE) to NeutralType.Float(FloatPrecision.SINGLE),
        NeutralType.Float(FloatPrecision.DOUBLE) to NeutralType.Float(FloatPrecision.DOUBLE),
        NeutralType.Decimal(10, 2) to NeutralType.Decimal(10, 2),
        NeutralType.Text(maxLength = 255) to NeutralType.Text(maxLength = 255),
        NeutralType.Char(length = 10) to NeutralType.Char(length = 10),
        NeutralType.Identifier(autoIncrement = true) to NeutralType.Identifier(autoIncrement = true),

        // Abflachungen — die Spalte traegt die Unterscheidung nicht.
        // identifier ohne autoIncrement ist ein blankes INT.
        NeutralType.Identifier() to NeutralType.Integer,
        // NVARCHAR/NCHAR tragen hoechstens 4000 Zeichen (W136).
        NeutralType.Text() to NeutralType.Text(),
        NeutralType.Text(maxLength = 5000) to NeutralType.Text(),
        NeutralType.Char(length = 5000) to NeutralType.Text(),
        // DECIMAL kappt bei Praezision 38 (W139).
        NeutralType.Decimal(50, 4) to NeutralType.Decimal(38, 4),
        // Kein nativer Typ in T-SQL → NVARCHAR(MAX) (W137/W132, ADR 0015).
        NeutralType.Json to NeutralType.Text(),
        NeutralType.Array(elementType = "text") to NeutralType.Text(),
        NeutralType.FullText to NeutralType.Text(),
        NeutralType.Email to NeutralType.Text(maxLength = NeutralType.Email.MAX_LENGTH),
    )

    test("canonical projection matches the T-SQL storage reality") {
        for ((type, canonical) in expected) {
            withClue(type) { canon.canonicalize(type) shouldBe canonical }
        }
    }

    test("enum folds onto the bounded NVARCHAR the column helper renders") {
        // Der Spalten-Helfer rendert NVARCHAR(<laengster Wert>) + CHECK, nicht
        // NVARCHAR(MAX) — der Kanonisierer muss dieselbe Breite projizieren.
        canon.canonicalize(NeutralType.Enum(values = listOf("red", "green"))) shouldBe
            NeutralType.Text(maxLength = 5)
        canon.canonicalize(NeutralType.Enum(values = listOf(""))) shouldBe NeutralType.Text(maxLength = 1)
        // Ohne Werte bleibt nur unbegrenzter Text.
        canon.canonicalize(NeutralType.Enum()) shouldBe NeutralType.Text()
    }

    test("enum with a refType stays identity (needs the schema's custom types)") {
        val referenced = NeutralType.Enum(refType = "mood")
        canon.canonicalize(referenced) shouldBe referenced
        // Auch mit Inline-Werten: der Helfer bevorzugt den Custom-Type und
        // koennte auf dem Domain-Pfad landen — das entscheidet nur das Schema.
        val both = NeutralType.Enum(values = listOf("a"), refType = "mood")
        canon.canonicalize(both) shouldBe both
    }

    test("geometry folds onto geometry/geography — SQL Server carries SRID per value") {
        // Geodaetische SRIDs (EPSG 4000-4999) rendern als geography und lesen
        // mit dem Default-SRID 4326 zurueck (R345); alles andere ist planares
        // geometry ohne SRID. Anders als bei PG/MySQL/SQLite rekonstruiert der
        // Reverse hier nichts — die Projektion IST die Speicherrealitaet.
        canon.canonicalize(NeutralType.Geometry(GeometryType.of("point"), srid = 4326)) shouldBe
            NeutralType.Geometry(srid = 4326)
        canon.canonicalize(NeutralType.Geometry(GeometryType.of("polygon"), srid = 4258)) shouldBe
            NeutralType.Geometry(srid = 4326)
        canon.canonicalize(NeutralType.Geometry(GeometryType.of("point"), srid = 3857)) shouldBe
            NeutralType.Geometry()
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
