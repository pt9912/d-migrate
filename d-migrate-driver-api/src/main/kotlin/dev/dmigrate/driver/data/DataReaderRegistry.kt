package dev.dmigrate.driver.data

import dev.dmigrate.driver.DatabaseDialect

/**
 * Registry für [DataReader] und [TableLister] pro [DatabaseDialect].
 *
 * Für 0.3.0 ein einfacher In-Memory-Mechanismus mit Registrierung beim
 * Klassenladen — kein ServiceLoader, weil die Treiber ohnehin kompiliert
 * mitgeliefert werden. ServiceLoader wird in 0.6.0 relevant, wenn externe
 * Treiber als separate JARs hinzukommen (analog zur DDL-Registry-Diskussion
 * im 0.2.0-Plan §6.2).
 *
 * Nutzung im CLI:
 * ```
 * val reader = DataReaderRegistry.dataReader(dialect)
 * val lister = DataReaderRegistry.tableLister(dialect)
 * ```
 *
 * Treiber registrieren sich beim Klassenladen über ihre
 * `*Driver.register()`-Methode oder direkt aus dem CLI-Bootstrap.
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
