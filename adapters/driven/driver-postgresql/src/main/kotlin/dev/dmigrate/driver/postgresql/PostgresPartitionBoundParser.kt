package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType

/**
 * Parst die von `pg_get_expr(pg_class.relpartbound, …)` gelieferte
 * `FOR VALUES`-Klausel einer Kind-Partition in das strukturierte
 * [PartitionDefinition]-Modell (ADR 0019 — „kein Native-Passthrough im
 * neutralen Modell"). Das ist der vom Slice markierte **Bug-Hotspot** —
 * daher voll unit-getestet (eigener Test je Strategie + Edge-Cases).
 *
 * `pg_get_expr` rendert je Strategie:
 * - RANGE: `FOR VALUES FROM (a, b) TO (c, d)` — Bound-Tupel, ggf. `MINVALUE`/`MAXVALUE`.
 * - LIST:  `FOR VALUES IN (v1, v2, …)`.
 * - HASH:  `FOR VALUES WITH (modulus M, remainder R)` (Schlüsselwörter klein).
 * - DEFAULT: `DEFAULT`.
 *
 * **Kanonisierung der Literale:** ein nachgestellter `::typ`-Cast (PG rendert
 * z. B. `'2022-02-01 00:00:00+00'::timestamp with time zone`) wird auf
 * Top-Level entfernt; Quoting/Whitespace bleiben unverändert, weil das Literal
 * unverändert valides Ziel-DDL ist (Generate konkateniert es). Sentinels werden
 * zu [PartitionBound.MinValue]/[PartitionBound.MaxValue] gehoben.
 *
 * Der Scanner toggelt `inQuote` an jedem `'`. Ein verdoppeltes `''` (escaptes
 * Quote) toggelt zweimal hintereinander ohne Zeichen dazwischen — die Quote-
 * Bilanz bleibt korrekt, ohne Sonderfall-Lookahead.
 */
internal object PostgresPartitionBoundParser {

    fun parse(name: String, boundExpr: String, type: PartitionType): PartitionDefinition {
        val expr = boundExpr.trim()
        if (expr.equals("DEFAULT", ignoreCase = true)) {
            return PartitionDefinition(name = name, isDefault = true)
        }
        return when (type) {
            PartitionType.RANGE -> parseRange(name, expr)
            PartitionType.LIST -> parseList(name, expr)
            PartitionType.HASH -> parseHash(name, expr)
        }
    }

    private fun parseRange(name: String, expr: String): PartitionDefinition {
        val fromKw = expr.indexOf("FROM", ignoreCase = true)
        require(fromKw >= 0) { "RANGE partition '$name' bound has no FROM clause: $expr" }
        val (fromInner, afterFrom) = parenGroup(expr, fromKw, name)
        val toKw = expr.indexOf("TO", afterFrom, ignoreCase = true)
        require(toKw >= 0) { "RANGE partition '$name' bound has no TO clause: $expr" }
        val (toInner, _) = parenGroup(expr, toKw, name)
        return PartitionDefinition(
            name = name,
            from = splitTopLevel(fromInner).map { it.toBound() },
            to = splitTopLevel(toInner).map { it.toBound() },
        )
    }

    private fun parseList(name: String, expr: String): PartitionDefinition {
        val (inner, _) = parenGroup(expr, 0, name)
        return PartitionDefinition(
            name = name,
            values = splitTopLevel(inner).map { stripCast(it) },
        )
    }

    private fun parseHash(name: String, expr: String): PartitionDefinition {
        val (inner, _) = parenGroup(expr, 0, name)
        var modulus: Int? = null
        var remainder: Int? = null
        for (part in splitTopLevel(inner)) {
            val tokens = part.trim().split(WHITESPACE)
            if (tokens.size < 2) continue
            when (tokens[0].lowercase()) {
                "modulus" -> modulus = tokens[1].toIntOrNull()
                "remainder" -> remainder = tokens[1].toIntOrNull()
            }
        }
        return PartitionDefinition(name = name, modulus = modulus, remainder = remainder)
    }

    private fun String.toBound(): PartitionBound {
        val value = stripCast(this)
        return when {
            value.equals("MINVALUE", ignoreCase = true) -> PartitionBound.MinValue
            value.equals("MAXVALUE", ignoreCase = true) -> PartitionBound.MaxValue
            else -> PartitionBound.Value(value)
        }
    }

    /**
     * Inhalt der ersten ausbalancierten Klammergruppe ab [startIdx] plus den
     * Index direkt hinter der schließenden Klammer. Respektiert Quotes (Klammern
     * in String-Literalen zählen nicht).
     */
    private fun parenGroup(s: String, startIdx: Int, name: String): Pair<String, Int> {
        val open = s.indexOf('(', startIdx)
        require(open >= 0) { "partition '$name' bound expected '(' in: $s" }
        var depth = 0
        var inQuote = false
        var i = open
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\'' -> inQuote = !inQuote
                inQuote -> Unit
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return s.substring(open + 1, i) to (i + 1)
                }
            }
            i++
        }
        error("partition '$name' bound has unbalanced parentheses: $s")
    }

    /** Komma-Trennung auf Top-Level (respektiert Quotes + Klammern/Brackets). */
    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        val token = StringBuilder()
        var depth = 0
        var inQuote = false
        for (c in s) {
            when {
                c == '\'' -> { inQuote = !inQuote; token.append(c) }
                inQuote -> token.append(c)
                c == '(' || c == '[' -> { depth++; token.append(c) }
                c == ')' || c == ']' -> { depth--; token.append(c) }
                c == ',' && depth == 0 -> { parts.addTrimmed(token); token.clear() }
                else -> token.append(c)
            }
        }
        parts.addTrimmed(token)
        return parts
    }

    private fun MutableList<String>.addTrimmed(token: StringBuilder) {
        val trimmed = token.toString().trim()
        if (trimmed.isNotEmpty()) add(trimmed)
    }

    /**
     * Entfernt einen nachgestellten `::typ`-Cast auf Top-Level (außerhalb von
     * Quotes/Klammern) und gibt das Literal sonst unverändert zurück.
     */
    private fun stripCast(value: String): String {
        val s = value.trim()
        var depth = 0
        var inQuote = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\'' -> inQuote = !inQuote
                inQuote -> Unit
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth--
                c == ':' && depth == 0 && i + 1 < s.length && s[i + 1] == ':' ->
                    return s.substring(0, i).trimEnd()
            }
            i++
        }
        return s
    }

    private val WHITESPACE = Regex("\\s+")
}
