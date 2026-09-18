package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Was der PostgreSQL-Reverse verliert und ab jetzt sagt.
 *
 * - **P8/`R402`:** `json` kommt als neutrales `json` und wird `jsonb`
 *   gerendert — beim Uebertragen aendern sich die Daten. `jsonb` selbst
 *   verliert nichts.
 * - **P9/`R404`:** `numeric` ohne Praezision wird Gleitkomma, an der Spalte
 *   und im Feld eines zusammengesetzten Typs.
 * - **N1/`R301`:** ein Array unbekannter Elementart und ein unbekannter
 *   Feldtyp fallen auf `text` — `spec/type-mapping.md` Abschnitt 8 verlangt
 *   dafuer seit jeher eine Note; sie fehlte an beiden Stellen.
 * - **S1:** eine `integer`-Identity mit `ALWAYS` als alleiniger
 *   Primaerschluessel behaelt ihren Modus, statt als `identifier` zu lesen
 *   (der rendert `SERIAL` und nimmt gesetzte Werte an).
 */
class PostgresReverseLossNoteTest : FunSpec({

    fun column(
        dataType: String,
        udtName: String = dataType,
        precision: Int? = null,
        scale: Int? = null,
        isPkCol: Boolean = false,
        isIdentity: Boolean = false,
        identityGeneration: String? = null,
        colDefault: String? = null,
    ) = PostgresTypeMapping.mapColumn(
        PostgresTypeMapping.ColumnInput(
            dataType = dataType,
            udtName = udtName,
            isPkCol = isPkCol,
            isIdentity = isIdentity,
            identityGeneration = identityGeneration,
            colDefault = colDefault,
            generatedSequenceName = null,
            charMaxLen = null,
            numPrecision = precision,
            numScale = scale,
            tableName = "t",
            colName = "c",
        ),
    )

    fun SchemaReadNote.pin(code: String) {
        this.code shouldBe code
        this.severity shouldBe SchemaReadSeverity.WARNING
        this.objectName shouldBe "t.c"
    }

    // ── P8 ────────────────────────────────────────────────────

    test("json meldet R402 und nennt, was beim Rendern als jsonb verloren geht") {
        val result = column("json")
        result.type shouldBe NeutralType.Json
        result.note!!.pin("R402")
        result.note!!.message shouldContain "jsonb"
        result.note!!.message shouldContain "key order"
    }

    test("jsonb meldet nichts — es verliert nichts") {
        val result = column("jsonb")
        result.type shouldBe NeutralType.Json
        result.note.shouldBeNull()
    }

    test("json[] traegt dieselbe Note wie eine json-Spalte") {
        val result = column("array", udtName = "_json")
        result.type shouldBe NeutralType.Array("json")
        result.note!!.pin("R402")
    }

    test("jsonb[] meldet nichts") {
        column("array", udtName = "_jsonb").note.shouldBeNull()
    }

    // L2: `CREATE TYPE … AS (…)` schreibt den Feldtyp durch denselben Mapper
    // wie eine Spalte — ein `json`-Feld entsteht am Ziel also als `jsonb`,
    // mit demselben Verlust. Es blieb als einzige json-Stelle still.
    test("ein json-Feld eines zusammengesetzten Typs meldet R402, ein jsonb-Feld nicht") {
        val field = PostgresTypeMapping.compositeField("json", "addr.payload")
        field.type shouldBe NeutralType.Json
        field.note!!.code shouldBe "R402"
        field.note!!.severity shouldBe SchemaReadSeverity.WARNING
        field.note!!.objectName shouldBe "addr.payload"
        field.note!!.message shouldContain "key order"

        PostgresTypeMapping.compositeField("jsonb", "addr.payload_b").note.shouldBeNull()
    }

    // ── P9 ────────────────────────────────────────────────────

    test("numeric ohne Praezision meldet R404 und wird float") {
        val result = column("numeric")
        result.type shouldBe NeutralType.Float()
        result.note!!.pin("R404")
        result.note!!.message shouldContain "without precision"
    }

    test("numeric mit Praezision meldet nichts") {
        val result = column("numeric", precision = 12, scale = 2)
        result.type shouldBe NeutralType.Decimal(12, 2)
        result.note.shouldBeNull()
    }

    test("ein Feld eines zusammengesetzten Typs meldet denselben Verlust") {
        val field = PostgresTypeMapping.compositeField("numeric", "addr.amount")
        field.type shouldBe NeutralType.Float()
        field.note!!.code shouldBe "R404"
        field.note!!.objectName shouldBe "addr.amount"

        PostgresTypeMapping.compositeField("numeric(10,2)", "addr.bounded").note.shouldBeNull()
    }

    // ── N1 ────────────────────────────────────────────────────

    test("ein Array unbekannter Elementart meldet R301 und liest text") {
        val result = column("array", udtName = "_date")
        result.type shouldBe NeutralType.Array("text")
        result.note!!.pin("R301")
        result.note!!.message shouldContain "date"
    }

    test("ein Array bekannter Elementart meldet nichts") {
        column("array", udtName = "_int8").note.shouldBeNull()
        column("array", udtName = "_text").note.shouldBeNull()
    }

    test("ein unbekannter Feldtyp eines zusammengesetzten Typs meldet R301") {
        val field = PostgresTypeMapping.compositeField("hstore", "addr.extra")
        field.type shouldBe NeutralType.Text()
        field.note!!.code shouldBe "R301"
    }

    // ── S1 ────────────────────────────────────────────────────

    test("integer GENERATED ALWAYS AS IDENTITY als alleiniger PK behaelt den Modus") {
        val result = column(
            "integer", udtName = "int4", isPkCol = true, isIdentity = true, identityGeneration = "ALWAYS",
        )
        result.type shouldBe NeutralType.Integer
        (result.generation as ColumnGeneration.Identity).mode shouldBe IdentityMode.ALWAYS
    }

    // Gegenprobe: `BY DEFAULT` und `serial` behalten den `identifier`-Vertrag —
    // dort verspricht das Modell nichts, was das Rendern bricht.
    test("BY DEFAULT und serial bleiben identifier") {
        column("integer", udtName = "int4", isPkCol = true, isIdentity = true, identityGeneration = "BY DEFAULT")
            .type shouldBe NeutralType.Identifier(autoIncrement = true)

        val serial = column(
            "integer", udtName = "int4", isPkCol = true, colDefault = "nextval('t_c_seq'::regclass)",
        )
        serial.type shouldBe NeutralType.Identifier(autoIncrement = true)
        serial.generation.shouldBeNull()
    }

    // H1 (Eigner, 2026-09-18): **nicht** `smallint`. Das neutrale Modell
    // traegt eine Identity nur auf `integer` und `biginteger` (`E130`), und
    // kein Generator rendert eine `smallint`-Identity — als `smallint` +
    // `identity(always)` gelesen braeche `schema generate` aus dem eigenen
    // Reverse ab.
    //
    // S6 (Eigner, 2026-09-18): Der Verlust bleibt, ist aber nicht mehr still —
    // `R406` benennt ihn. Verlustfrei waere nur eine Modellerweiterung, und
    // die hat der Eigner abgelehnt: drei der fuenf Generatoren kennen die Form
    // ohnehin nicht (`SmallIntIdentityRenderTest`).
    test("smallint mit ALWAYS behaelt den identifier-Vertrag und meldet R406") {
        val result = column(
            "smallint", udtName = "int2", isPkCol = true, isIdentity = true, identityGeneration = "ALWAYS",
        )
        result.type shouldBe NeutralType.Identifier(autoIncrement = true)
        result.generation.shouldBeNull()
        result.note!!.pin("R406")
    }

    // Dieselbe Grenze **ohne** Schluessel. Hier lieferte der Zweig fuer
    // Nicht-Schluesselspalten bis S6 `smallint` + `identity` — eine Form, die
    // `E130` ablehnt, aus der sich also gar nicht generieren liess.
    test("smallint mit ALWAYS ohne Schluessel wird identifier und meldet R406") {
        val result = column("smallint", udtName = "int2", isIdentity = true, identityGeneration = "ALWAYS")
        result.type shouldBe NeutralType.Identifier(autoIncrement = true)
        result.note!!.pin("R406")
    }

    // Gegenproben: `BY DEFAULT` verliert nichts, was das Modell verspricht —
    // `SERIAL` nimmt ausdrueckliche Werte an, genau wie `BY DEFAULT`. Ein
    // `smallserial` traegt gar keine Erzeugungsangabe.
    test("smallint BY DEFAULT und smallserial melden nichts") {
        column("smallint", udtName = "int2", isPkCol = true, isIdentity = true, identityGeneration = "BY DEFAULT")
            .note.shouldBeNull()

        val smallserial = column(
            "smallint", udtName = "int2", isPkCol = true, colDefault = "nextval('t_c_seq'::regclass)",
        )
        smallserial.type shouldBe NeutralType.Identifier(autoIncrement = true)
        smallserial.note.shouldBeNull()
    }

    // Und die Breite ist die Grenze, nicht die Erzeugung: `integer` ohne
    // Schluessel behaelt die Identity wie bisher.
    test("integer ohne Schluessel behaelt die Identity") {
        val result = column("integer", udtName = "int4", isIdentity = true, identityGeneration = "ALWAYS")
        result.type shouldBe NeutralType.Integer
        (result.generation as ColumnGeneration.Identity).mode shouldBe IdentityMode.ALWAYS
        result.note.shouldBeNull()
    }

    // Der Fingerabdruck-Kanonisierer ruft `mapColumn` ohne PK- und
    // Identity-Kontext; seine Projektion von `identifier(auto)` bleibt
    // unberuehrt (er traegt dafuer ohnehin eine eigene Ausnahme).
    test("der Kanonisierer sieht identifier(auto) unveraendert") {
        PostgresNeutralTypeCanonicalizer.canonicalize(NeutralType.Identifier(autoIncrement = true)) shouldBe
            NeutralType.Identifier(autoIncrement = true)
    }
})
