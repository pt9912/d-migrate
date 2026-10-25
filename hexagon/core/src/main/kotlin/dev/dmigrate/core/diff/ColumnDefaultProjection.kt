package dev.dmigrate.core.diff

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import java.math.BigDecimal

/**
 * Was Datei und Datenbank an einem Spalten-`default` **gleich** meinen (Issue #54).
 *
 * Zwei Dinge unterscheiden sich in der Darstellung, nicht im Wert:
 *
 * * **Der Zahlentyp.** Der Datei-Parser liefert `Int` (bzw. `Long`/`BigInteger`) fuer eine
 *   Ganzzahl und `Double` fuer eine Kommazahl; jeder Reader liest den Katalogtext mit
 *   `toLongOrNull()`/`toDoubleOrNull()`. `NumberLiteral` ist eine data class — `0` (Int) ist
 *   nicht `0L` und nicht `0.0`. Zwei Zahlenliterale gelten deshalb als gleich, wenn sie
 *   **numerisch** gleich sind ([canonicalNumber]).
 * * **Der Wahrheitswert.** Faltet der Zieldialekt `boolean` auf `integer` (SQLite: `INTEGER`),
 *   liest der Reader `0`/`1`, nie `false`/`true`. Der Reader hat die Typinformation
 *   „boolean“ nicht; der Vergleich hat sie auf der Datei-Seite. [project] bildet dort
 *   `BooleanLiteral` auf `0`/`1` ab.
 *
 * Wirkt nur auf die Vergleichsentscheidung und den Fingerabdruck; die geplante Operation traegt
 * die unveraenderte Definition (wie bei [TargetProjection]).
 */
object ColumnDefaultProjection {

    /**
     * Der Default so, wie ihn der Zieldialekt ablegt. [canonicalizeType] ist die Typ-Projektion des
     * Ziels ([TargetProjection.type]); faltet sie `boolean` auf `integer`, wird ein `BooleanLiteral`
     * an einer `boolean`-Spalte zu `0`/`1`.
     */
    fun project(
        type: NeutralType,
        default: DefaultValue?,
        canonicalizeType: (NeutralType) -> NeutralType,
    ): DefaultValue? {
        if (default !is DefaultValue.BooleanLiteral || type != NeutralType.BooleanType) return default
        if (canonicalizeType(NeutralType.BooleanType) != NeutralType.Integer) return default
        return DefaultValue.NumberLiteral(if (default.value) 1 else 0)
    }

    /** Gleich im Sinne des Vergleichs: Zahlenliterale numerisch, alles andere wie bisher per `==`. */
    fun same(left: DefaultValue?, right: DefaultValue?): Boolean =
        if (left is DefaultValue.NumberLiteral && right is DefaultValue.NumberLiteral) {
            canonicalNumber(left.value) == canonicalNumber(right.value)
        } else {
            left == right
        }

    /**
     * Die kanonische Schreibweise einer Zahl: ohne Exponent, ohne nachlaufende Nullen
     * (`2.0` → `2`, `1e3` → `1000`, `0.0` → `0`). Der Fingerabdruck traegt sie, damit er mit dem
     * Vergleich uebereinstimmt.
     */
    fun canonicalNumber(value: Number): String {
        val text = value.toString()
        return runCatching { BigDecimal(text).stripTrailingZeros().toPlainString() }.getOrDefault(text)
    }
}
