package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.NeutralType

/**
 * Explicit whitelist of safe `(source, target)` `NeutralType` pairs for
 * the `INSERT INTO temp SELECT CAST(col AS <newType>) FROM orig` step
 * of the SQLite RebuildTable pipeline. Phase G.1.
 *
 * Background: the renderer used to emit `CAST(col AS <SqliteTypeMapper>)`
 * unconditionally for every type-changed column. SQLite is forgiving
 * (no `CAST` raises an error — unparseable input becomes 0 / 0.0 /
 * empty string), so a silent precision/range/format loss could survive
 * an `AlterColumnType` rebuild without diagnostic. This matrix replaces
 * the heuristic: pairs outside the matrix block the rebuild with
 * `MANUAL_ACTION_REQUIRED` + diagnostic `SQLITE_CAST_NOT_WHITELISTED`.
 *
 * Whitelisted groups:
 *
 * - **Integer family** — `SmallInt`, `Integer`, `BigInteger` are stored
 *   in SQLite as native 8-byte INTEGER without width-constraint, so
 *   `CAST(x AS INTEGER)` is identity. Range-truncation is a downstream-
 *   dialect concern (PG/MySQL) and not the SQLite renderer's job.
 * - **Text family with non-narrowing length** — `Text(a)→Text(b)`,
 *   `Char(a)→Char(b)`, `Char(a)→Text(b)`, `Text(a)→Char(b)` where the
 *   target length covers the source length. SQLite stores both as TEXT
 *   without enforcement, but the cast remains lossless for downstream
 *   dialects that *do* enforce length.
 * - **Date→DateTime(tz=false)** — Date is a subset of DateTime; both
 *   stored as ISO-8601 TEXT in SQLite.
 *
 * Explicit carve-outs (not whitelisted; documented in §10):
 *
 * - `Float`↔`Decimal` — REAL precision loss.
 * - `BooleanType`↔Integer-family — existing rows may hold non-0/1.
 * - `DateTime(tz=true)`↔`DateTime(tz=false)` — TZ-info loss.
 * - `DateTime`→`Date`/`Time` — component loss.
 * - `Text`→`Integer`/`Float`/`Uuid`/`Enum` — silent 0/0.0/invalid-but-stored.
 * - `Integer`/`Float`→`Text` — lossless in SQLite but bidirectionally
 *   risky for downstream-dialect re-migration; out of plan scope.
 * - `Binary`↔`Text` — open question per Plan §G.1.
 * - Narrowing lengths (`Text(20)→Text(10)`, `Char(20)→Char(10)`) — truncation.
 * - `Email` mappings — Email carries a semantic 254-char invariant
 *   that lives outside the structural matrix; defer to a follow-up.
 * - `Identifier` — carries `AUTOINCREMENT` semantics, not a plain type.
 *
 * Live-data preflights (e.g. `SELECT COUNT(*) WHERE CAST(x) IS NULL`
 * for would-be unsafe casts) require a connected source DB and are a
 * separate Phase-G carve-out for 0.9.8+.
 */
internal object SqliteCastMatrix {

    fun isWhitelisted(source: NeutralType, target: NeutralType): Boolean {
        if (source == target) return true
        if (isIntegerFamily(source) && isIntegerFamily(target)) return true
        if (isDateToDateTime(source, target)) return true
        return isTextFamilyNonNarrowing(source, target)
    }

    /**
     * Human-readable description of why a `(source, target)` pair is
     * not whitelisted. Used in the diagnostic message so operators can
     * tell at-a-glance whether the block is structural (wrong group)
     * or a length-narrowing issue.
     */
    fun describeBlock(source: NeutralType, target: NeutralType): String = when {
        isTextFamilyNonNarrowing(target, source) ->
            "target length is shorter than source length (would truncate existing rows)"
        isIntegerFamily(source) && !isIntegerFamily(target) ||
            !isIntegerFamily(source) && isIntegerFamily(target) ->
            "Integer<->non-Integer cast (silent 0/0.0 or unintended numeric coercion possible)"
        else ->
            "(source, target) type pair not in the SQLite Cast-Matrix whitelist (Phase G.1)"
    }

    private fun isIntegerFamily(t: NeutralType): Boolean =
        t is NeutralType.SmallInt || t is NeutralType.Integer || t is NeutralType.BigInteger

    private fun isDateToDateTime(source: NeutralType, target: NeutralType): Boolean =
        source is NeutralType.Date && target is NeutralType.DateTime && !target.timezone

    private fun isTextFamilyNonNarrowing(source: NeutralType, target: NeutralType): Boolean {
        val srcLen = textFamilyLength(source) ?: return false
        val dstLen = textFamilyLength(target) ?: return false
        return covers(srcLen, dstLen)
    }

    /**
     * Returns the effective length for the four members of the
     * "structural Text family" considered by the matrix:
     *
     * - `Text(null)` → unbounded
     * - `Text(N)` → bounded N
     * - `Char(N)` → bounded N
     *
     * Returns `null` for any neutral type that is not in this
     * structural Text family. (DateTime/Date/Time/Uuid/Json/Xml/Enum/
     * Array/Email all map to SQLite TEXT but carry semantic invariants
     * that the structural length-coverage rule cannot model.)
     */
    private fun textFamilyLength(t: NeutralType): TextLen? = when (t) {
        is NeutralType.Text -> {
            val max = t.maxLength
            if (max == null) TextLen.Unbounded else TextLen.Bounded(max)
        }
        is NeutralType.Char -> TextLen.Bounded(t.length)
        else -> null
    }

    private fun covers(source: TextLen, target: TextLen): Boolean = when {
        target is TextLen.Unbounded -> true
        source is TextLen.Unbounded -> false
        source is TextLen.Bounded && target is TextLen.Bounded -> target.length >= source.length
        else -> false
    }

    private sealed class TextLen {
        data object Unbounded : TextLen()
        data class Bounded(val length: Int) : TextLen()
    }
}
