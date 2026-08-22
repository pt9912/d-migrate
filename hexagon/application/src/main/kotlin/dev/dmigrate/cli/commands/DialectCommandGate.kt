package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect

/**
 * Kommando-Grenz-Gate für Dialekte im slice-weisen Treiber-Ausbau
 * ([ADR 0047]): Kommandos, deren MSSQL-Pfad noch nicht gebaut ist, weisen
 * den Dialekt an ihrer Grenze ab, bevor Treiber-Ports berührt werden.
 * Welche Kommandos ab welchem Slice verfügbar sind, steht in der
 * Kommando-Verfügbarkeits-Tabelle des Plan-Dokuments
 * (docs/planning/in-progress/mssql-dialect-scoping.md); der Slice, der
 * einen Pfad liefert, entfernt sein Kommando hier aus [GatedCommand]
 * und damit fällt das Gate für diesen Pfad weg.
 *
 * `when`-Zweige HINTER einem Gate (z. B. Profiling-Adapter-Auswahl)
 * dürfen für MSSQL mit `error("unreachable: …")` auf dieses Gate
 * verweisen — sie sind durch die Kommando-Grenze nicht erreichbar.
 */
object DialectCommandGate {

    /** Kommandos, deren MSSQL-Pfad noch nicht gebaut ist. */
    enum class GatedCommand(val display: String) {
        SCHEMA_MIGRATE("schema migrate"),
        DATA_PROFILE("data profile"),
    }

    /**
     * Liefert die Ablehnungs-Meldung, wenn [dialect] für [command] noch
     * nicht verfügbar ist, sonst `null`. Aufrufer geben die Meldung auf
     * ihrem Fehlerkanal aus und beenden mit Exit 2 (Usage-/Config-Fehler);
     * MCP-Handler übersetzen sie in ihre Validation-Fehlerform.
     */
    fun refusal(command: GatedCommand, dialect: DatabaseDialect): String? =
        if (dialect == DatabaseDialect.MSSQL) {
            "${command.display} does not support dialect mssql yet " +
                "(MSSQL rollout, ADR 0047). Commands available for mssql: $AVAILABLE_FOR_MSSQL."
        } else {
            null
        }

    /** Nutzersichtbare Liste der für mssql freigeschalteten Kommandos (wächst mit jedem Slice). */
    const val AVAILABLE_FOR_MSSQL: String =
        "schema reverse, schema compare, schema generate, export flyway/liquibase/django/knex, " +
            "data export/import/transfer"
}
