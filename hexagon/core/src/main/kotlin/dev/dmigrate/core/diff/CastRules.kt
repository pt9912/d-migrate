package dev.dmigrate.core.diff

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import java.math.BigInteger

/**
 * Wann ein Cast in einem rohen Ausdruck **nur Schreibweise** ist — gemessen an
 * der Spalte, mit der der gecastete Operand verglichen wird (ADR 0056: „ein
 * Cast auf den Typ, den der Operand ohnehin hat").
 *
 * Die Typnamen sind die, in denen PostgreSQL einen Cast zurueckgibt; der
 * `::`-Cast ist ohnehin nur dort Syntax. Sie gelten nur **kleingeschrieben**
 * und ohne Modifikator: ein quotierter Typname (`"TEXT"`) waere ein anderer
 * Typ, und `varchar(2)` kuerzt.
 *
 * Wo der Vergleich den Typ einer Spalte nicht sicher kennt, faellt nichts:
 * `citext` und andere benutzerdefinierte Typen (im Modell `enum` mit
 * `ref_type`), Wahrheitswerte, JSON und alle uebrigen Typen haben hier keine
 * Familie.
 */
internal enum class CastFamily {
    TEXT, CHAR, INTEGER, DECIMAL, FLOAT, DATE, TIMESTAMP, TIME;

    companion object {
        fun of(type: NeutralType): CastFamily? = when (type) {
            is NeutralType.Text, NeutralType.Email -> TEXT
            is NeutralType.Char -> CHAR
            NeutralType.SmallInt, NeutralType.Integer, NeutralType.BigInteger, is NeutralType.Identifier -> INTEGER
            is NeutralType.Decimal -> DECIMAL
            is NeutralType.Float -> FLOAT
            NeutralType.Date -> DATE
            is NeutralType.DateTime -> TIMESTAMP
            NeutralType.Time -> TIME
            else -> null
        }
    }
}

internal object CastRules {

    /** Alle Typnamen, die eine Regel unten kennt — mehr muss der Scanner nicht erkennen. */
    val KNOWN_TYPES: Set<String> by lazy {
        TEXT_TYPES + CHAR_TYPES + INTEGER_WIDTHS.keys + NUMERIC_TYPES + DOUBLE_TYPES + REAL_TYPES +
            DATE_TYPES + TIMESTAMP_TYPES + TIMESTAMPTZ_TYPES + TIME_TYPES
    }

    /** Die laengste Folge von Woertern, die einen Typnamen bildet. */
    const val MAX_TYPE_WORDS = 4

    /**
     * Ob `spalte::[type]` den Wert der Spalte unveraendert laesst — und die
     * Spalte dabei so vergleicht, wie sie es ohne Cast taete.
     *
     * Eine Zeichenkette variabler Laenge auf einen Texttyp; eine Ganzzahl auf
     * einen gleich breiten oder breiteren Ganzzahltyp oder auf `numeric`; ein
     * `numeric` auf `numeric`. **Nicht** `char(n)` auf `text` (streicht die
     * Leerzeichen am Ende), nicht auf Gleitkomma, und nie unter `LIKE` ausser
     * bei Text.
     */
    fun columnCastKeeps(columnType: NeutralType, type: String, like: Boolean): Boolean {
        val family = CastFamily.of(columnType) ?: return false
        if (like && family != CastFamily.TEXT) return false
        return when (family) {
            CastFamily.TEXT -> type in TEXT_TYPES
            CastFamily.INTEGER -> type in NUMERIC_TYPES || (INTEGER_WIDTHS[type] ?: 0) >= widthOf(columnType)
            CastFamily.DECIMAL -> type in NUMERIC_TYPES
            else -> false
        }
    }

    /**
     * Ob `literal::[type]` gegen eine Spalte vom Typ [columnType] dasselbe
     * ergibt wie das unmarkierte Literal. Das unmarkierte String-Literal nimmt
     * in PostgreSQL den Typ der Spalte an; ein Zahl-Literal hat seinen eigenen
     * und wird im Vergleich genau dann gleich behandelt, wenn [type] dieselbe
     * Familie traegt.
     */
    fun literalCastKeeps(columnType: NeutralType, literal: CastLiteral, type: String, like: Boolean): Boolean {
        val family = CastFamily.of(columnType) ?: return false
        if (like) return family == CastFamily.TEXT && literal.string && type in TEXT_TYPES
        return when (family) {
            CastFamily.TEXT -> literal.string && type in TEXT_TYPES
            CastFamily.CHAR -> literal.string && type in CHAR_TYPES
            CastFamily.INTEGER -> integerLiteralKeeps(columnType, literal, type)
            CastFamily.DECIMAL -> type in NUMERIC_TYPES
            CastFamily.FLOAT -> floatLiteralKeeps(columnType, literal, type)
            CastFamily.DATE -> literal.string && type in DATE_TYPES
            CastFamily.TIMESTAMP -> literal.string && type in timestampTypes(columnType)
            CastFamily.TIME -> literal.string && type in TIME_TYPES
        }
    }

    /** Ob [type] zu den Typen gehoert, deren Operand sich wie Text vergleicht. */
    fun isTextType(type: String): Boolean = type in TEXT_TYPES

    /** Ob [type] eine Ganzzahl oder `numeric` ist — der Partner einer Zahl-Spalte. */
    fun isExactNumericType(type: String): Boolean = type in NUMERIC_TYPES || type in INTEGER_WIDTHS

    /**
     * Ein Zahl-Literal auf einen Ganzzahltyp: nur eine ganze Zahl, die in den
     * Typ passt (`70000::smallint` scheitert, `70000` nicht). Ein
     * String-Literal nur auf **genau** den Typ der Spalte — das unmarkierte
     * nimmt ihn an (`'70000'` scheitert an einer `smallint`-Spalte,
     * `'70000'::integer` nicht).
     */
    private fun integerLiteralKeeps(columnType: NeutralType, literal: CastLiteral, type: String): Boolean {
        val width = INTEGER_WIDTHS[type] ?: return false
        if (literal.string) return columnType !is NeutralType.Identifier && width == widthOf(columnType)
        val digits = literal.text
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return false
        return BigInteger(digits) <= INTEGER_MAX.getValue(width)
    }

    /**
     * Ein Zahl-Literal vergleicht PostgreSQL mit einer `real`- **und** einer
     * `double precision`-Spalte als `double precision` — es schreibt den Cast
     * dorthin selbst (`(0)::double precision`). Ein Cast auf `real` rundet das
     * Literal und bleibt deshalb stehen. Ein String-Literal nimmt den Typ der
     * Spalte an; nur dieser Typ ist Schreibweise.
     */
    private fun floatLiteralKeeps(columnType: NeutralType, literal: CastLiteral, type: String): Boolean {
        val double = (columnType as NeutralType.Float).floatPrecision == FloatPrecision.DOUBLE
        return when {
            !literal.string -> type in DOUBLE_TYPES
            double -> type in DOUBLE_TYPES
            else -> type in REAL_TYPES
        }
    }

    private fun timestampTypes(columnType: NeutralType): Set<String> =
        if ((columnType as NeutralType.DateTime).timezone) TIMESTAMPTZ_TYPES else TIMESTAMP_TYPES

    /**
     * Die Breite einer Ganzzahlspalte. Ein `identifier` ist in PostgreSQL
     * `integer` oder `smallint`, anderswo auch breiter — er zaehlt deshalb als
     * die breiteste, damit nur ein Cast auf `bigint` faellt.
     */
    private fun widthOf(columnType: NeutralType): Int = when (columnType) {
        NeutralType.SmallInt -> SMALL
        NeutralType.Integer -> REGULAR
        else -> BIG
    }

    private const val SMALL = 2
    private const val REGULAR = 4
    private const val BIG = 8

    private val TEXT_TYPES = setOf("text", "varchar", "character varying")

    /** Nur `bpchar`: `character` und `char` ohne Laenge sind `char(1)` und kuerzen. */
    private val CHAR_TYPES = setOf("bpchar")

    private val INTEGER_WIDTHS = mapOf(
        "smallint" to SMALL, "int2" to SMALL,
        "integer" to REGULAR, "int" to REGULAR, "int4" to REGULAR,
        "bigint" to BIG, "int8" to BIG,
    )

    private val INTEGER_MAX = mapOf(
        SMALL to BigInteger.valueOf(Short.MAX_VALUE.toLong()),
        REGULAR to BigInteger.valueOf(Int.MAX_VALUE.toLong()),
        BIG to BigInteger.valueOf(Long.MAX_VALUE),
    )

    private val NUMERIC_TYPES = setOf("numeric", "decimal")

    /** `float` ohne Angabe ist in PostgreSQL `double precision`. */
    private val DOUBLE_TYPES = setOf("double precision", "float8", "float")
    private val REAL_TYPES = setOf("real", "float4")
    private val DATE_TYPES = setOf("date")
    private val TIMESTAMP_TYPES = setOf("timestamp", "timestamp without time zone")
    private val TIMESTAMPTZ_TYPES = setOf("timestamptz", "timestamp with time zone")
    private val TIME_TYPES = setOf("time", "time without time zone")
}

/** Das Literal vor einem Cast: ein String-Literal oder eine Zahl ([text]). */
internal data class CastLiteral(val string: Boolean, val text: String)
