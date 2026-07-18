package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * Rendert die **Wert-Seite** einer MySQL-Partitionsgrenze (RANGE/LIST COLUMNS) — getrennt von der
 * **Struktur-Seite** ([MysqlIndexPartitionDdlHelper]: Klauseln, Index-Heben, FK-Carve-Outs).
 *
 * **Vertrag:** Partitionsgrenzen-Literale tragen ihr SQL-Quoting bereits im neutralen Modell
 * (so liefert der PG-Reverse sie, so projiziert der Fingerprint sie, so vergleicht der Comparator
 * sie). Der Renderer **fügt also keine Quotes hinzu** — das täte nur eine Dialekt-Seite und erzeugte
 * Drift (dieselbe unquotierte Definition ergäbe valides MySQL-, aber invalides PG-DDL; vgl.
 * `PartitionLiteralGuard`: „identically for both dialects"). Quoting ist Sache von Fixture/Spec.
 *
 * Der einzige **MySQL-spezifische** Eingriff ist die Zeitzonen-Normalisierung (AP6-Review P1 #1 /
 * P2 #2): MySQL-`DATETIME` kennt keine Zone. Eine Temporal-Grenze ist `'<datum>[ <zeit>][<offset>]'`,
 * strukturiert zerlegt (kein Regex-String-Chirurgie):
 *  - **kein Offset** (reine Datums-/Zeitgrenze) → unverändert. Der frühere unverankerte Regex fraß
 *    das `-DD` einer Datumsgrenze als Phantom-Zone und verschob still die Grenze.
 *  - **UTC**-Offset (`+00`, …, oder `Z`) → entfernt (instant-erhaltend) → W129 (einmal pro Tabelle).
 *  - **Nicht-UTC**-Offset → Grenze bleibt **unverändert** (kein stiller Shift) → E061 **pro Partition**
 *    (das DDL scheitert laut, statt Zeilen falsch zu platzieren).
 *
 * Eine nicht erkennbare Form bleibt unverändert (kein Raten).
 */
internal class MysqlPartitionBoundRenderer {

    /** Temporal-Schlüsseltyp (DATE/DATETIME) — nur dort greift die tz-Normalisierung. */
    fun isTemporal(type: NeutralType?): Boolean =
        type is NeutralType.DateTime || type == NeutralType.Date

    /**
     * Rendert einen bereits gegen Injection geprüften (`PartitionLiteralGuard`) Literal passend
     * zum Schlüsseltyp. Temporal → tz-Normalisierung; sonst unverändert (das Modell trägt das Quoting).
     * Abschließend werden Backslashes MySQL-gerecht verdoppelt (siehe [escapeBackslashForMysql]).
     */
    fun renderColumnBoundLiteral(
        safe: String,
        keyType: NeutralType?,
        partitionName: String,
        notes: MutableList<TransformationNote>,
        emittedCodes: MutableSet<String>,
    ): String {
        val rendered = if (isTemporal(keyType)) normalizeTimezone(safe, partitionName, notes, emittedCodes) else safe
        return escapeBackslashForMysql(rendered)
    }

    /**
     * MySQL-only: verdoppelt Backslashes **innerhalb** eines bereits gequoteten String-Literals.
     *
     * Das Modell trägt die Grenze fertig gequotet und SQL-standard-escaped (inneres `'` als `''`).
     * MySQL behandelt — anders als PG mit `standard_conforming_strings` — `\` als Escape-Zeichen; ein
     * auf `\` endender Wert escapte sonst sein schließendes Quote weg und ließe folgenden DDL-Text aus
     * der `VALUES …`-Klausel ausbrechen (CWE-89). Wir legen deshalb **nur** die Backslash-Verdopplung
     * auf das vorhandene Quoting; [dev.dmigrate.driver.SqlIdentifiers.quoteStringLiteral] passt hier
     * bewusst **nicht** (es startet von einem unquotierten Wert und würde die bereits verdoppelten
     * Quotes erneut escapen). Unquotierte Grenzen (numerisch, `MAXVALUE`) tragen keinen Backslash und
     * bleiben unangetastet — PG braucht kein solches Escaping und emittiert das Literal verbatim, also
     * entsteht kein Cross-Dialect-Drift.
     */
    private fun escapeBackslashForMysql(literal: String): String =
        if (isSingleQuoted(literal)) "'${literal.substring(1, literal.length - 1).replace("\\", "\\\\")}'" else literal

    /** §2 (ADR 0020): siehe Klassen-KDoc. Entfernt nur einen UTC-Offset; quotet sonst nichts hinzu. */
    private fun normalizeTimezone(
        literal: String,
        partitionName: String,
        notes: MutableList<TransformationNote>,
        emittedCodes: MutableSet<String>,
    ): String {
        val match = TEMPORAL_LITERAL.matchEntire(unwrapSingleQuotes(literal)) ?: return literal
        val offset = match.groupValues[OFFSET_GROUP].ifEmpty { null } ?: return literal
        if (!isUtcOffset(offset)) {
            notes += ManualActionRequired(
                code = "E061", objectType = "partition", objectName = partitionName,
                reason = "Partition '$partitionName' bound carries a non-UTC timezone offset '$offset'; " +
                    "stripping it for MySQL DATETIME would shift the boundary, so the bound was kept " +
                    "unchanged and the generated DDL will not execute until it is resolved.",
                hint = "Re-read the source with the session time zone set to UTC, or convert the bound to UTC.",
            ).toNote()
            return literal
        }
        if (emittedCodes.add("W129")) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W129", objectName = partitionName,
                message = "PostgreSQL timestamptz partition bounds normalized to UTC (timezone suffix " +
                    "removed) for MySQL DATETIME, which has no time zone.",
                hint = "Ensure the source data is stored/interpreted as UTC.",
            )
        }
        // Instant ohne Offset, ursprüngliches Quoting beibehalten (Strip-Pfad = Transformation).
        val time = match.groupValues[TIME_GROUP]
        val instant = if (time.isEmpty()) match.groupValues[DATE_GROUP] else "${match.groupValues[DATE_GROUP]} $time"
        return "'$instant'"
    }

    /** `Z`/`z` (Zulu) und die expliziten +00-Schreibweisen sind UTC. */
    private fun isUtcOffset(offset: String): Boolean =
        offset.equals("Z", ignoreCase = true) || offset in UTC_OFFSETS

    private fun isSingleQuoted(s: String): Boolean =
        s.length >= 2 && s.first() == '\'' && s.last() == '\''

    private fun unwrapSingleQuotes(s: String): String =
        if (isSingleQuoted(s)) s.substring(1, s.length - 1) else s

    private companion object {
        /**
         * `date[ T|t]time[offset]`, vollständig verankert (`^…$`). Der Offset (`±HH[:MM]` oder `Z`)
         * sitzt **innerhalb** der zeit-tragenden Gruppe, wird also nur erkannt, wenn eine
         * Zeitkomponente vorausgeht (verhindert den Phantom-Offset-Bug aus AP6-Review #1). Akzeptiert
         * Leerzeichen, `T` und `t` als ISO-8601-Trenner.
         */
        val TEMPORAL_LITERAL = Regex(
            "^(\\d{4}-\\d{2}-\\d{2})(?:[ Tt](\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?)([+-]\\d{2}(?::?\\d{2})?|[Zz])?)?$",
        )
        const val DATE_GROUP = 1
        const val TIME_GROUP = 2
        const val OFFSET_GROUP = 3
        val UTC_OFFSETS = setOf("+00", "+0000", "+00:00", "-00", "-0000", "-00:00")
    }
}
