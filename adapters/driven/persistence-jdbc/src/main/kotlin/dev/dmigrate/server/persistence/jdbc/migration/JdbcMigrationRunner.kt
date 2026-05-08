package dev.dmigrate.server.persistence.jdbc.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import javax.sql.DataSource

/**
 * Wendet die Phase-E Server-State-Migrationen via Flyway an. Plan-Ref:
 * `ImpPlan-0.9.6-E2.md` § 3.2 + § 4.
 *
 * Production-Pfad ist ein expliziter Ops-Migrationslauf (Plan § 3.2
 * + § 10 Q3). Auto-Migrate beim Server-Start ist opt-in via
 * `server.state.migrations.auto = true` und wird vom Bootstrap im
 * MCP-Adapter — nicht in diesem Runner — gewrapped.
 *
 * Lokationen: `classpath:db/migration` (V1__phase_e_initial.sql und
 * spaetere V2/V3-Migrationen werden hier abgelegt). Eine isolierte
 * History-Tabelle `flyway_phase_e_history` haelt das Phase-E-Schema
 * von eventuellen Co-Mietern in derselben DB getrennt.
 */
class JdbcMigrationRunner(
    private val dataSource: DataSource,
    private val schemaHistoryTable: String = DEFAULT_HISTORY_TABLE,
    private val migrationLocations: Array<String> = arrayOf(DEFAULT_LOCATION),
) {

    fun migrate(): MigrateResult =
        configure().migrate()

    /**
     * `validate` prueft Drift zwischen Classpath-Migrationen und der
     * History-Tabelle, ohne neue Migrationen anzuwenden. Wird vom
     * Bootstrap-Pfad bei `server.state.migrations.auto = false`
     * verwendet, um beim Server-Start gegen schief stehende DBs zu
     * fail-fasten (Plan § 3.2).
     */
    fun validate() {
        configure().validate()
    }

    private fun configure(): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(*migrationLocations)
            .table(schemaHistoryTable)
            .baselineOnMigrate(false)
            .load()

    companion object {
        const val DEFAULT_HISTORY_TABLE: String = "flyway_phase_e_history"
        const val DEFAULT_LOCATION: String = "classpath:db/migration"
    }
}
