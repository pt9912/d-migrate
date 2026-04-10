package dev.dmigrate.driver.data

import dev.dmigrate.driver.DatabaseDialect

/**
 * Registry für [DataReader] und [TableLister] pro [DatabaseDialect].
 *
 * **Bootstrap (Phase E)**: Es gibt KEINE automatische Self-Registration beim
 * Klassenladen. Treiber werden über ihre `*Driver.register()`-Bootstrap-
 * Objects (z.B. `dev.dmigrate.driver.postgresql.PostgresDriver`) in der
 * globalen Registry eingetragen. Diese werden in Phase E (CLI-Integration,
 * [dev.dmigrate.cli.Main]) einmalig vor dem ersten Command-Dispatch
 * aufgerufen — siehe [JdbcUrlBuilderRegistry] für das vollständige Beispiel.
 *
 * **Phase B (heute)**: Tests rufen `*Driver.register()` direkt in ihrem
 * `beforeSpec` auf. Im normalen Build-Pfad wird die Registry noch nicht
 * benutzt, weil Phase E den CLI-Aufruf-Pfad noch nicht implementiert hat.
 *
 * Nutzung im CLI (ab Phase E):
 * ```
 * val reader = DataReaderRegistry.dataReader(dialect)
 * val lister = DataReaderRegistry.tableLister(dialect)
 * ```
 *
 * ServiceLoader-basierte Auto-Discovery kommt in 0.6.0, wenn externe
 * Treiber-JARs dazukommen.
 */
object DataReaderRegistry {

    private val readers = mutableMapOf<DatabaseDialect, DataReader>()
    private val listers = mutableMapOf<DatabaseDialect, TableLister>()

    /** Registriert einen [DataReader] für seinen Dialekt. Vorhandene Einträge werden überschrieben. */
    @Synchronized
    fun registerDataReader(reader: DataReader) {
        readers[reader.dialect] = reader
    }

    /** Registriert einen [TableLister] für seinen Dialekt. */
    @Synchronized
    fun registerTableLister(lister: TableLister) {
        listers[lister.dialect] = lister
    }

    /** @throws IllegalArgumentException wenn kein Reader für diesen Dialekt registriert ist */
    fun dataReader(dialect: DatabaseDialect): DataReader =
        readers[dialect] ?: throw IllegalArgumentException(
            "No DataReader registered for dialect $dialect. " +
                "Make sure the driver module (d-migrate-driver-${dialect.name.lowercase()}) is on the classpath " +
                "and DataReaderRegistry.registerDataReader has been called."
        )

    /** @throws IllegalArgumentException wenn kein TableLister für diesen Dialekt registriert ist */
    fun tableLister(dialect: DatabaseDialect): TableLister =
        listers[dialect] ?: throw IllegalArgumentException(
            "No TableLister registered for dialect $dialect."
        )

    /** Setzt die Registry zurück. Für Tests; auch aus anderen Modulen aufrufbar. */
    @Synchronized
    fun clear() {
        readers.clear()
        listers.clear()
    }
}
