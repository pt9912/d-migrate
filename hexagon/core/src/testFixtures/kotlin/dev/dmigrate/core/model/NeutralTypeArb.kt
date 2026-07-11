package dev.dmigrate.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string

/**
 * Geteilter Generator für die gesamte [NeutralType]-Hierarchie (LN-046, ADR 0029).
 *
 * Deckt alle 21 Varianten — 12 parameterlose Literale + 9 parametrische — uniform
 * ab, damit Property-Tests (TypeMapper-Totalität, Kanonisierer-Idempotenz,
 * Fingerprint-Ordnungsunabhängigkeit) jeden Zweig treffen und eine neu ergänzte
 * Variante laut auffällt. Wohnt in den Test-Fixtures, damit Core und die
 * Driver-Module eine einzige Quelle teilen.
 */
fun Arb.Companion.neutralType(): Arb<NeutralType> {
    val geometryType = Arb.of(GeometryType.KNOWN_VALUES.toList() + "unknownshape").map { GeometryType(it) }

    val identifier: Arb<NeutralType> = Arb.boolean().map { NeutralType.Identifier(autoIncrement = it) }
    val text: Arb<NeutralType> = Arb.int(1..8000).orNull().map { NeutralType.Text(maxLength = it) }
    val char: Arb<NeutralType> = Arb.int(1..255).map { NeutralType.Char(length = it) }
    val float: Arb<NeutralType> = Arb.enum<FloatPrecision>().map { NeutralType.Float(floatPrecision = it) }
    val decimal: Arb<NeutralType> =
        Arb.bind(Arb.int(1..38), Arb.int(0..38)) { p, s -> NeutralType.Decimal(precision = p, scale = minOf(s, p)) }
    val dateTime: Arb<NeutralType> = Arb.boolean().map { NeutralType.DateTime(timezone = it) }
    // YAML-sicheres Token: führender Buchstabe ∉ {y,n,t,f,o} + [a-z0-9]-Suffix.
    // Damit kann der Wert nie ein YAML-1.1-Implicit-Scalar sein (Zahl, Bool
    // `yes/no/on/off/true/false`, Null `~/null`) — dieselbe Disziplin, mit der
    // SchemaArb schon seine Bezeichner präfixiert. So misst das PBT den
    // strukturellen Round-Trip, nicht YAML-Quoting-/Typ-Inferenz-Artefakte. Der
    // beliebige-String-Round-Trip des Codecs ist getrennte Folgearbeit:
    // docs/planning/open/yaml-codec-arbitrary-string-roundtrip.md.
    fun yamlSafeToken(maxTail: Int): Arb<String> = Arb.bind(
        Arb.of(('a'..'z').toList() - listOf('y', 'n', 't', 'f', 'o')),
        Arb.list(Arb.of(('a'..'z').toList() + ('0'..'9')), 0..maxTail),
    ) { head, tail -> head + tail.joinToString("") }

    val enum: Arb<NeutralType> = Arb.bind(
        // Werteliste ist null oder NICHT-leer: ein Enum ohne Werte ist ein
        // ungültiges Schema (der Reverse-Reader erzeugt es nie). Der Fingerprint
        // unterscheidet `Enum([])` (→ enum()) von `Enum(null)` (→ enum), während
        // der YAML-Codec beide zu null normalisiert — nur für diesen degenerierten
        // Fall bräche der Round-Trip (LN-046 Phase C, per PBT gefunden).
        Arb.list(yamlSafeToken(7), 1..4).orNull(),
        yamlSafeToken(11).orNull(),
    ) { values, refType -> NeutralType.Enum(values = values, refType = refType) }
    val array: Arb<NeutralType> =
        Arb.of("text", "integer", "boolean", "uuid", "custom").map { NeutralType.Array(elementType = it) }
    val geometry: Arb<NeutralType> =
        Arb.bind(geometryType, Arb.int(0..40000).orNull()) { g, srid -> NeutralType.Geometry(geometryType = g, srid = srid) }

    val literals: Arb<NeutralType> = Arb.of(
        NeutralType.Integer, NeutralType.SmallInt, NeutralType.BigInteger, NeutralType.BooleanType,
        NeutralType.Date, NeutralType.Time, NeutralType.Uuid, NeutralType.Json, NeutralType.Xml,
        NeutralType.Binary, NeutralType.Email, NeutralType.FullText,
    )

    return Arb.choice(identifier, text, char, float, decimal, dateTime, enum, array, geometry, literals)
}
