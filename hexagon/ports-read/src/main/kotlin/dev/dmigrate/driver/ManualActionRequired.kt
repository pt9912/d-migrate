package dev.dmigrate.driver

/**
 * Structured representation of a DDL generation decision that requires
 * manual intervention.
 *
 * Each instance maps to the existing DDL diagnostic contract:
 * - as [TransformationNote] with [NoteType.ACTION_REQUIRED]
 * - optionally as [SkippedObject] when no executable DDL was produced
 */
data class ManualActionRequired(
    val code: String,
    val objectType: String,
    val objectName: String,
    val reason: String,
    val hint: String? = null,
    val sourceDialect: String? = null,
) {
    /** Maps to an ACTION_REQUIRED [TransformationNote]. */
    fun toNote(phase: DdlPhase? = null): TransformationNote = TransformationNote(
        type = NoteType.ACTION_REQUIRED,
        code = code,
        objectName = objectName,
        message = reason,
        hint = hint,
        phase = phase,
    )

    /** Maps to a [SkippedObject] when no DDL was generated. */
    fun toSkipped(phase: DdlPhase? = null): SkippedObject = SkippedObject(
        type = objectType,
        name = objectName,
        reason = reason,
        code = code,
        hint = hint,
        phase = phase,
    )

    /**
     * Meldet ein Objekt in **einem** Zug: die Notiz, und — weil ein
     * `ManualActionRequired` nur entsteht, wo kein DDL erzeugt wurde — den
     * übersprungenen Eintrag dazu.
     *
     * Die beiden Mapper oben bleiben einzeln erreichbar, denn es gibt einen
     * echten Fall für [toNote] allein: SQL Server rendert eine kaskadierende
     * Aktion als `NO ACTION`, der Fremdschlüssel steht also **in** der DDL,
     * und die Notiz ist trotzdem fällig
     * (`MssqlColumnConstraintHelper.buildForeignKeyClause`).
     *
     * Wer beides braucht, ruft diese Funktion. Dann kann er den
     * [SkippedObject] nicht mehr vergessen — genau das war an sieben
     * Aufrufstellen passiert, und es hat drei Runden gebraucht, bis es
     * auffiel.
     */
    fun record(
        notes: MutableList<TransformationNote>,
        skipped: MutableList<SkippedObject>?,
        phase: DdlPhase? = null,
    ) {
        notes += toNote(phase)
        skipped?.add(toSkipped(phase))
    }

    /**
     * Das Statement eines uebersprungenen Objekts: leer, mit der Notiz — und
     * ueber [record] mit dem gezaehlten Verlust.
     *
     * Die Regelform fuer „dieses Objekt kommt nicht in die Ausgabe". Sechs
     * Dialekt-Helfer trugen sie vorher als eigene private Funktion, die **nur**
     * die Notiz baute; der Skip stand daneben und wurde an fuenf Stellen
     * vergessen (MSSQL `E066`/`E070`/`E071`). Mit dieser Signatur muss der
     * Aufrufer den Zaehler mitgeben — vergessen laesst er sich nicht mehr.
     *
     * @param statementPhase die Phase des **Statements** (Default `PRE_DATA`
     *   wie bei [DdlStatement]).
     * @param skipPhase die Phase des [SkippedObject]; `null` (Default) heisst
     *   „nicht phasen-gebunden" — die Bedeutung, die `DdlModelTest` festhaelt.
     *   Nur wo das Statement ausdruecklich `POST_DATA` traegt, gehoert sie
     *   gesetzt, sonst faellt der Eintrag aus beiden Eimern.
     */
    fun skippedStatement(
        skipped: MutableList<SkippedObject>?,
        statementPhase: DdlPhase = DdlPhase.PRE_DATA,
        skipPhase: DdlPhase? = null,
    ): DdlStatement {
        val notes = mutableListOf<TransformationNote>()
        record(notes, skipped, skipPhase)
        return DdlStatement("", notes, phase = statementPhase)
    }

    /** Legacy helper for callers that still need a plain TODO-style comment. */
    fun toTodoComment(): String = buildString {
        append("-- TODO: $reason")
        if (hint != null) append(" ($hint)")
    }
}
