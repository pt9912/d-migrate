package dev.dmigrate.core.seed

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.validation.SchemaValidator
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

/**
 * `data seed` P1 kann für [NeutralType.Geometry], [NeutralType.FullText]
 * und eine [NeutralType.Enum] ohne `values` keinen Wert erzeugen — siehe
 * ImpPlan-1.3.0-cli-data-seed-p1.md AE-10. `TableRowSeeder` (AP2)
 * entscheidet anhand von `required`/`nullable`, ob das `null` oder ein
 * Preflight-Fehler wird.
 */
class UnsupportedSeedTypeException(val type: NeutralType) :
    RuntimeException("Kann für NeutralType $type in P1 keinen Wert generieren (siehe AE-10)")

/**
 * Deterministischer, abhängigkeitsfreier Wertegenerator für `data seed`
 * P1. Reine Funktion eines [Random]-Zustands: derselbe Zustand liefert
 * denselben Wert, unabhängig von Systemzeit oder Aufrufreihenfolge außer
 * dem [Random]-Verbrauch selbst.
 *
 * Erzeugt je [NeutralType] denselben Java-Laufzeittyp, den der
 * bestehende `data import`-Pfad für denselben Neutraltyp produziert
 * (`adapters/driven/formats/.../data/converters/TypeConverters.kt`),
 * damit die `DataWriter`-Implementierungen ihn ohne Sonderfall binden
 * (AE-9): Integer-Familie → `Long`, `Decimal` → `BigDecimal`,
 * `Date`/`Time`/`DateTime` → `java.time`-Typen, `Uuid` → `java.util.UUID`,
 * `Binary` → `ByteArray`, `Array` → `List<Any?>`.
 *
 * Aufgeteilt nach AE-12 (parametrische/entscheidungstragende Zweige vs.
 * einfache Literal-Zweige in [simpleGenerate]), analog zum
 * `PostgresTypeMapper`-Split, um unter der Detekt-Komplexitätsgrenze zu
 * bleiben.
 */
class ColumnValueGenerator(private val random: Random, private val locale: SeedLocale) {

    fun generate(type: NeutralType): Any? = when (type) {
        is NeutralType.Identifier -> randomLong(1, IDENTIFIER_BOUND)
        is NeutralType.Text -> randomText(type.maxLength)
        is NeutralType.Char -> randomLetters(type.length.coerceAtLeast(1))
        is NeutralType.Decimal -> randomDecimal(type.precision, type.scale)
        is NeutralType.DateTime -> randomDateTime(type.timezone)
        is NeutralType.Enum -> randomEnumValue(type)
        is NeutralType.Array -> randomArray(type.elementType)
        is NeutralType.Geometry -> throw UnsupportedSeedTypeException(type)
        is NeutralType.FullText -> throw UnsupportedSeedTypeException(type)
        else -> simpleGenerate(type)
    }

    private fun simpleGenerate(type: NeutralType): Any = when (type) {
        is NeutralType.SmallInt -> randomLong(SMALLINT_MIN, SMALLINT_BOUND)
        is NeutralType.Integer -> randomLong(INTEGER_MIN, INTEGER_BOUND)
        is NeutralType.BigInteger -> randomLong(BIGINT_MIN, BIGINT_BOUND)
        is NeutralType.Float -> random.nextDouble(-FLOAT_BOUND, FLOAT_BOUND)
        is NeutralType.BooleanType -> random.nextBoolean()
        is NeutralType.Date -> randomDate()
        is NeutralType.Time -> randomTime()
        is NeutralType.Uuid -> randomUuid()
        is NeutralType.Json -> randomJson()
        is NeutralType.Xml -> randomXml()
        is NeutralType.Binary -> random.nextBytes(BINARY_LENGTH)
        is NeutralType.Email -> randomEmail()
        else -> error("simpleGenerate aufgerufen fuer einen parametrischen/unbehandelten NeutralType: $type")
    }

    private fun randomLong(fromInclusive: Long, untilExclusive: Long): Long =
        random.nextLong(fromInclusive, untilExclusive)

    /**
     * Rendert eine `--rules`-`template`-Regel (P2, AP3) aus bereits geparsten
     * [TemplateSegment]en. Nutzt denselben [random]-Zustand wie alle anderen
     * `generate`-Zweige -- deterministisch bei gleichem Seed. `{digits:N}`
     * zieht `N` unabhängige Ziffern zeichenweise (Führungsnullen bleiben
     * erhalten), analog [randomLetters].
     */
    fun renderTemplate(segments: List<TemplateSegment>): String = buildString {
        for (segment in segments) {
            when (segment) {
                is TemplateSegment.Literal -> append(segment.text)
                TemplateSegment.Word -> append(randomWord())
                is TemplateSegment.Digits -> repeat(segment.count) { append(random.nextInt(0, DIGIT_BOUND)) }
                TemplateSegment.Uuid -> append(randomUuid())
            }
        }
    }

    private fun randomWord(): String = locale.words[random.nextInt(locale.words.size)]

    private fun randomText(maxLength: Int?): String {
        val wordCount = random.nextInt(1, MAX_TEXT_WORDS + 1)
        val text = (1..wordCount).joinToString(" ") { randomWord() }
        return if (maxLength != null && text.length > maxLength) text.take(maxLength) else text
    }

    private fun randomLetters(length: Int): String =
        (1..length).joinToString("") { ('a'..'z').random(random).toString() }

    private fun randomDecimal(precision: Int, scale: Int): BigDecimal {
        val digits = precision.coerceIn(1, MAX_DECIMAL_DIGITS)
        val bound = pow10(digits)
        val unscaled = randomLong(0, bound)
        val signed = if (random.nextBoolean()) unscaled else -unscaled
        return BigDecimal(signed).movePointLeft(scale.coerceAtLeast(0))
    }

    private fun randomDate(): LocalDate = LocalDate.ofEpochDay(randomLong(EPOCH_DAY_MIN, EPOCH_DAY_MAX))

    private fun randomTime(): LocalTime = LocalTime.ofSecondOfDay(randomLong(0, SECONDS_PER_DAY))

    private fun randomDateTime(timezone: Boolean): Any {
        val local = LocalDateTime.of(randomDate(), randomTime())
        return if (timezone) local.atOffset(ZoneOffset.UTC) else local
    }

    private fun randomUuid(): UUID = UUID(random.nextLong(), random.nextLong())

    private fun randomJson(): String = """{"seed":${random.nextInt(JSON_SEED_BOUND)},"value":"${randomWord()}"}"""

    private fun randomXml(): String = "<seed value=\"${randomWord()}\"/>"

    private fun randomEmail(): String {
        val local = "${randomWord()}${random.nextInt(EMAIL_SUFFIX_BOUND)}"
        val domain = locale.emailDomains[random.nextInt(locale.emailDomains.size)]
        return "$local@$domain"
    }

    private fun randomEnumValue(type: NeutralType.Enum): String {
        val values = type.values
        if (values.isNullOrEmpty()) throw UnsupportedSeedTypeException(type)
        return values[random.nextInt(values.size)]
    }

    private fun randomArray(elementTypeName: String): List<Any?> {
        val elementType = arrayElementNeutralType(elementTypeName)
        val size = random.nextInt(1, MAX_ARRAY_ELEMENTS + 1)
        return (1..size).map { generate(elementType) }
    }

    /**
     * Bildet `NeutralType.Array.elementType` auf einen internen
     * [NeutralType] ab. Lokal nachgebaut statt aus `formats` importiert,
     * weil `hexagon:core` nicht von `formats` abhängen darf (AE-11).
     * `enum`/`array` als Elementtyp tragen keine ausreichenden Metadaten
     * (Wertevorrat bzw. Verschachtelung) — Fallback auf `Text`, analog
     * `PostgresTypeMapper.resolveElementType`.
     *
     * Das Namensvokabular wird NICHT hier dupliziert: die Mitgliedschaft
     * wird gegen [SchemaValidator.ARRAY_ELEMENT_TYPE_NAMES] geprüft (das
     * bereits von der Schema-Validierung genutzte Vokabular), bevor die
     * eigentliche Namen→Typ-Abbildung greift. Ein Name außerhalb dieses
     * Vokabulars — z. B. weil `BASE_TYPE_NAMES` um einen neuen Basistyp
     * erweitert wurde, ohne diese Abbildung nachzuziehen — wirft
     * [UnsupportedSeedTypeException] statt still auf `Text`
     * zurückzufallen (Konsistenz mit Geometry/FullText/wertlosem Enum).
     */
    private fun arrayElementNeutralType(name: String): NeutralType {
        val normalized = name.trim().lowercase()
        if (normalized !in SchemaValidator.ARRAY_ELEMENT_TYPE_NAMES) {
            throw UnsupportedSeedTypeException(NeutralType.Array(name))
        }
        return when (normalized) {
            "identifier" -> NeutralType.Identifier()
            "text" -> NeutralType.Text()
            "char" -> NeutralType.Char(DEFAULT_CHAR_LENGTH)
            "integer" -> NeutralType.Integer
            "smallint" -> NeutralType.SmallInt
            "biginteger" -> NeutralType.BigInteger
            "float" -> NeutralType.Float()
            "decimal" -> NeutralType.Decimal(DEFAULT_DECIMAL_PRECISION, DEFAULT_DECIMAL_SCALE)
            "boolean" -> NeutralType.BooleanType
            "datetime" -> NeutralType.DateTime()
            "date" -> NeutralType.Date
            "time" -> NeutralType.Time
            "uuid" -> NeutralType.Uuid
            "json" -> NeutralType.Json
            "xml" -> NeutralType.Xml
            "binary" -> NeutralType.Binary
            "email" -> NeutralType.Email
            "enum", "array" -> NeutralType.Text()
            else -> error(
                "arrayElementNeutralType: '$normalized' besteht die ARRAY_ELEMENT_TYPE_NAMES-Mitgliedschaft, " +
                    "hat aber keinen expliziten Zweig -- Abbildung nachziehen.",
            )
        }
    }

    private fun pow10(digits: Int): Long {
        var value = 1L
        repeat(digits) { value *= 10 }
        return value
    }

    companion object {
        private const val IDENTIFIER_BOUND = 1_000_000L
        private const val SMALLINT_MIN = -32_768L
        private const val SMALLINT_BOUND = 32_768L
        private const val INTEGER_MIN = -1_000_000L
        private const val INTEGER_BOUND = 1_000_001L
        private const val BIGINT_MIN = -1_000_000_000_000L
        private const val BIGINT_BOUND = 1_000_000_000_001L
        private const val FLOAT_BOUND = 1_000_000.0
        private const val BINARY_LENGTH = 16
        private const val MAX_DECIMAL_DIGITS = 18
        private const val DEFAULT_CHAR_LENGTH = 8
        private const val DEFAULT_DECIMAL_PRECISION = 10
        private const val DEFAULT_DECIMAL_SCALE = 2
        private const val MAX_TEXT_WORDS = 3
        private const val MAX_ARRAY_ELEMENTS = 3
        private const val JSON_SEED_BOUND = 1_000
        private const val EMAIL_SUFFIX_BOUND = 1_000
        private const val DIGIT_BOUND = 10
        private const val SECONDS_PER_DAY = 86_400L
        private val EPOCH_DAY_MIN = LocalDate.of(2000, 1, 1).toEpochDay()
        private val EPOCH_DAY_MAX = LocalDate.of(2035, 1, 1).toEpochDay()
    }
}
