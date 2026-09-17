package dev.dmigrate.driver

import dev.dmigrate.core.model.ReferentialAction

/**
 * Die SQL-Schreibweise einer referentiellen Aktion — **einmal**, fuer den
 * Generate- und den Migrate-Pfad jedes Dialekts.
 *
 * Die Abbildung ist der SQL-Standard und in PostgreSQL, MySQL und SQLite
 * woertlich dieselbe. Sie stand trotzdem viermal im Repo (in
 * [AbstractDdlGenerator] und in den drei `*DiffSqlBuilders`), und ein
 * Kommentar behauptete dabei „dieselbe Quelle wie im Generate-Pfad" — vier
 * Kopien sind vier Gelegenheiten, dass die Zusage eines Fremdschluessels in
 * einem Pfad anders lautet als im anderen.
 *
 * **SQL Server weicht bewusst ab** und bildet das nicht hier ab, sondern an
 * seiner Stelle: T-SQL kennt kein `RESTRICT`, und ohne aufschiebbare
 * Constraints ist `NO ACTION` dasselbe. Der MSSQL-Generator und sein
 * Diff-Builder setzen nur diesen einen Wert um und gehen fuer die uebrigen
 * hierueber.
 */
object ReferentialActions {

    fun sql(action: ReferentialAction): String = when (action) {
        ReferentialAction.RESTRICT -> "RESTRICT"
        ReferentialAction.CASCADE -> "CASCADE"
        ReferentialAction.SET_NULL -> "SET NULL"
        ReferentialAction.SET_DEFAULT -> "SET DEFAULT"
        ReferentialAction.NO_ACTION -> "NO ACTION"
    }
}
