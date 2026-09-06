package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.core.model.PartitionTemporalLiteral
import dev.dmigrate.driver.TransformationNote

/**
 * Die **Wert-Seite** einer Oracle-Partitionsgrenze — getrennt von der
 * Struktur-Seite ([OraclePartitionDdlBuilder]).
 *
 * Das neutrale Modell traegt Grenzen fertig gequotet (ADR 0019); der Renderer
 * quotet nichts nach. Sein einziger Eingriff ist der **Temporalfall**, und der
 * ist fuer Oracle nicht optional: ein blankes `'2024-01-01'` gegen eine
 * `DATE`-Spalte wird nicht als ISO-Datum gelesen, sondern gegen
 * `NLS_DATE_FORMAT` — per Default `DD-MON-RR`. Die Anweisung scheitert dann
 * mit `ORA-01861`, und zwar abhaengig von der Sitzung des Ausfuehrenden.
 * Deshalb wird die Grenze in eine explizite `TO_DATE`-/`TO_TIMESTAMP`-Form mit
 * mitgegebener Maske gesetzt.
 *
 * Die Zonenbehandlung folgt derselben Regel wie bei MySQL (ADR 0020): Oracles
 * `DATE` kennt keine Zone, ein Nicht-UTC-Offset wuerde die Grenze beim
 * Entfernen verschieben. Er bleibt deshalb stehen und die DDL scheitert laut
 * (`E061`), statt Zeilen still falsch zu platzieren.
 */
internal class OraclePartitionBoundRenderer {

    fun isTemporal(type: NeutralType?): Boolean =
        type is NeutralType.DateTime || type == NeutralType.Date

    fun render(
        safe: String,
        keyType: NeutralType?,
        partitionName: String,
        notes: MutableList<TransformationNote>,
        emittedCodes: MutableSet<String>,
    ): String {
        if (!isTemporal(keyType)) return safe
        // Keine erkennbare Temporalform: nicht raten, unveraendert lassen.
        val parts = PartitionTemporalLiteral.parse(safe) ?: return safe
        val offset = parts.offset
        if (offset != null && !PartitionTemporalLiteral.isUtcOffset(offset)) {
            notes += inexpressibleNote(
                partitionName,
                "carries a non-UTC timezone offset '$offset', which Oracle DATE cannot hold",
                "Re-read the source with the session time zone set to UTC, or convert the bound to UTC.",
            )
            return safe
        }
        if (parts.hasFraction) {
            // Live gemessen: Oracle NIMMT die Anweisung an und schneidet die
            // Bruchteilsekunde ab -- die Grenze verschiebt sich still um
            // weniger als eine Sekunde, und Zeilen wechseln die Partition.
            // Unumgesetzt scheitert die Anweisung stattdessen laut
            // (ORA-01861), wie beim Zeitzonen-Fall.
            notes += inexpressibleNote(
                partitionName,
                "carries sub-second precision, which Oracle DATE truncates (the boundary would shift)",
                "Round the bound to whole seconds, or partition on a column the model maps to a " +
                    "fractional-second type.",
            )
            return safe
        }
        if (offset != null && emittedCodes.add("W129")) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W129", objectName = partitionName,
                message = "Partition bound timezone offset removed for Oracle DATE, which has no time zone; " +
                    "the bound is interpreted as UTC.",
                hint = "Ensure the source data is stored/interpreted as UTC.",
            )
        }
        return conversion(parts)
    }

    /**
     * Eine Grenze, die Oracle nicht **exakt** ausdruecken kann. Sie bleibt
     * unumgesetzt stehen: die Anweisung scheitert dann laut (`ORA-01861`),
     * statt die Grenze still zu verschieben und Zeilen in eine andere
     * Partition zu legen. Auf dem Migrate-Pfad blockt der Diff-Renderer
     * darauf, statt sie auszufuehren.
     */
    private fun inexpressibleNote(
        partitionName: String,
        problem: String,
        hint: String,
    ): TransformationNote = ManualActionRequired(
        code = "E061", objectType = "partition", objectName = partitionName,
        reason = "Partition '$partitionName' bound $problem; the bound was kept unchanged so the " +
            "generated DDL will not execute until it is resolved, rather than silently shifting it.",
        hint = hint,
    ).toNote()

    /**
     * Die Maske wird aus der **Form des Literals** abgeleitet, nicht aus dem
     * Spaltentyp: eine reine Datumsgrenze mit der Zeitmaske zu lesen scheiterte
     * an der fehlenden Zeit, und umgekehrt verlore eine Zeitgrenze mit der
     * Datumsmaske ihren Zeitanteil — Oracle schneidet dabei still ab.
     */
    private fun conversion(parts: PartitionTemporalLiteral.Parts): String =
        if (parts.time != null) {
            "TO_DATE('${parts.instant}', 'YYYY-MM-DD HH24:MI:SS')"
        } else {
            "TO_DATE('${parts.date}', 'YYYY-MM-DD')"
        }
}
