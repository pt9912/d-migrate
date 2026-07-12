package dev.dmigrate.format.verify

import dev.dmigrate.verify.ValueCanonicalizationException
import java.math.BigDecimal
import java.math.BigInteger

/**
 * LN-009 / ADR 0030: numerische + boolesche Wert-Kanonik.
 *
 * Ausgelagert aus [CanonicalValueCodec], damit die Coercion-Regeln je Zahlen-
 * familie fokussiert testbar bleiben. Alle Formen kollidieren über Dialekt- und
 * Flattening-Grenzen: Boolean unter Integer/Decimal → `1`/`0`, Decimal ohne
 * trailing zeros, Float als kürzeste round-trip-Dezimale.
 */
internal object CanonicalNumeric {

    fun integral(value: Any): String = when (value) {
        is Boolean -> boolDigit(value)
        is BigInteger -> value.toString()
        is Byte, is Short, is Int, is Long -> (value as Number).toLong().toString()
        is BigDecimal -> value.stripTrailingZeros().toBigIntegerExact().toString()
        is Number -> value.toLong().toString()
        is String -> BigInteger(value.trim()).toString()
        else -> throw cannot(value, "Integer")
    }

    fun decimal(value: Any): String = when (value) {
        is BigDecimal -> normalize(value)
        is BigInteger -> value.toString()
        is Boolean -> boolDigit(value)
        is Number -> normalize(BigDecimal(value.toString()))
        is String -> normalize(BigDecimal(value.trim()))
        else -> throw cannot(value, "Decimal")
    }

    fun float(value: Any): String = when (value) {
        is Double -> double(value)
        // Float.toString liefert die kürzeste round-trip-Dezimale; gleich-breite
        // Floats über Dialekte kollidieren so (Breiten-Mismatch = Verifier-Exklusion).
        is Float -> if (value.isNaN() || value.isInfinite()) special(value.toDouble()) else value.toString()
        is BigDecimal -> normalize(value)
        is Number -> double(value.toDouble())
        is String -> double(value.trim().toDouble())
        else -> throw cannot(value, "Float")
    }

    fun boolean(value: Any): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toLong() != 0L
        is String -> when (value.trim().lowercase()) {
            "1", "t", "true", "y", "yes" -> true
            "0", "f", "false", "n", "no", "" -> false
            else -> throw cannot(value, "Boolean")
        }
        else -> throw cannot(value, "Boolean")
    }

    private fun boolDigit(value: Boolean): String = if (value) "1" else "0"

    /** stripTrailingZeros + toPlainString, mit "-0"→"0" und E-Notation-Schutz. */
    private fun normalize(bd: BigDecimal): String {
        val stripped = bd.stripTrailingZeros()
        val plain = (if (stripped.signum() == 0) BigDecimal.ZERO else stripped).toPlainString()
        return if (plain == "-0") "0" else plain
    }

    private fun double(d: Double): String = if (d.isNaN() || d.isInfinite()) special(d) else d.toString()

    private fun special(d: Double): String = when {
        d.isNaN() -> "NaN"
        d == Double.POSITIVE_INFINITY -> "Infinity"
        else -> "-Infinity"
    }

    private fun cannot(value: Any, expected: String): ValueCanonicalizationException =
        ValueCanonicalizationException("Wert der Klasse ${value.javaClass.name} nicht als $expected kanonisierbar")
}
