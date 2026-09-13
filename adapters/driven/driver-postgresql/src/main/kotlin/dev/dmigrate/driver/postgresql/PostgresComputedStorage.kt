package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.PostgresServerVersion
import dev.dmigrate.driver.RawSqlExpressionPortability
import dev.dmigrate.driver.TransformationNote

/**
 * Welche Speicherform eine berechnete Spalte auf **diesem** PostgreSQL bekommt.
 *
 * PostgreSQL ist der einzige der fuenf Dialekte, bei dem das nicht am Dialekt
 * haengt, sondern an seiner Version — gemessen an 18.6:
 *
 * | PostgreSQL | `VIRTUAL` | ohne Angabe |
 * | --- | --- | --- |
 * | 14 bis 17 | Syntaxfehler | `STORED` ist Pflichtwort |
 * | ab 18 | gueltig (`attgenerated = 'v'`) | virtuell |
 *
 * **Gefragt wird die Faehigkeitstabelle, nicht eine Zahl von hier.** Sonst
 * entstuende neben `ServerVersion`, `RoutineCapability` und den Ad-hoc-
 * Schwellen in den Renderern ein vierter Ort, an dem eine Version bewertet
 * wird — und genau das ist der Befund, den dieser Schnitt aufloest.
 */
internal object PostgresComputedStorage {

    /** Das Wort hinter der Klausel. */
    fun suffix(computed: ColumnGeneration.Computed, serverVersion: PostgresServerVersion?): String =
        if (!computed.stored && supportsVirtual(serverVersion)) "VIRTUAL" else "STORED"

    /** Die Spalte soll virtuell sein, das Ziel kennt die Form nicht. */
    fun isDegraded(computed: ColumnGeneration.Computed, serverVersion: PostgresServerVersion?): Boolean =
        !computed.stored && !supportsVirtual(serverVersion)

    private fun supportsVirtual(serverVersion: PostgresServerVersion?): Boolean =
        PostgresCapabilities.capabilities(serverVersion).supportsVirtualComputedColumns

    /**
     * Ob der Ausdruck auf PostgreSQL ueberhaupt gilt.
     *
     * Rohen Text uebersetzt der Reverse nicht — ein aus MySQL zurueckgelesener
     * Ausdruck mit Backticks ist hier kein gueltiger. Dann faellt die
     * Berechnung weg und die Spalte bleibt gewoehnlich; [refusalNote] sagt es.
     */
    fun isPortable(computed: ColumnGeneration.Computed): Boolean =
        RawSqlExpressionPortability.assess(computed.expression, DatabaseDialect.POSTGRESQL).portable

    /** Die Absage fuer einen Ausdruck, den PostgreSQL nicht parsen kann — sonst `null`. */
    fun refusalNote(colName: String, column: ColumnDefinition): TransformationNote? {
        val computed = column.generation as? ColumnGeneration.Computed ?: return null
        return RawSqlExpressionPortability.computedRefusal(colName, computed.expression, DatabaseDialect.POSTGRESQL)
    }

    /**
     * Die Meldung zur Degradierung — **nur** wo die Zielversion bekannt ist.
     *
     * Ist sie es nicht (ein Dateiziel hat keine), gilt die aktuellste gemessene,
     * und dann wird nicht degradiert. Es gibt also keinen Fall, in dem hier
     * gewarnt wuerde, ohne dass eine gemessene Version dahintersteht.
     */
    fun degradedMessage(colName: String, serverVersion: PostgresServerVersion?): String {
        val where = serverVersion?.let { "PostgreSQL ${it.major}.${it.minor}" }
            ?: "PostgreSQL below ${PostgresCapabilities.VIRTUAL_COMPUTED_SINCE_MAJOR}"
        return "Column '$colName' is declared as a virtual computed column, but $where has no virtual form; " +
            "it was rendered as STORED and the value is kept on disk."
    }

    private const val HINT: String =
        "Upgrade the target, or set `stored: true` so the schema says what the database does."

    /** Die Meldung fuer den generate-Pfad — `null`, wenn nichts degradiert wird. */
    fun degradedNote(
        tableName: String,
        colName: String,
        column: ColumnDefinition,
        serverVersion: PostgresServerVersion?,
    ): TransformationNote? {
        val computed = column.generation as? ColumnGeneration.Computed ?: return null
        if (!isDegraded(computed, serverVersion)) return null
        return TransformationNote(
            type = NoteType.WARNING,
            code = DEGRADED_TO_STORED,
            objectName = "$tableName.$colName",
            message = degradedMessage(colName, serverVersion),
            hint = HINT,
        )
    }

    /** Eine virtuelle Spalte wurde gespeichert gerendert. */
    const val DEGRADED_TO_STORED: String = "W158"
}
