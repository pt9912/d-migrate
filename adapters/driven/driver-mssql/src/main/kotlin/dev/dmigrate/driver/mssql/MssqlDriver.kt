package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.NeutralTypeCanonicalizer
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.TransferTypeCompatibility
import dev.dmigrate.driver.StructuralTransferTypeCompatibility
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister

/**
 * [DatabaseDriver] implementation for MSSQL.
 *
 * Reverse-read, DDL-generate and data-path surface (Slices 1–3). Die
 * verbleibenden Kommandos (`schema migrate`, `data profile`) weist
 * `DialectCommandGate` (ADR 0047) weiterhin an der Kommando-Grenze ab; die
 * uebrigen Capability-Methoden behalten ihre konservativen Interface-Defaults.
 */
class MssqlDriver : DatabaseDriver {
    override val dialect = DatabaseDialect.MSSQL
    override fun urlBuilder(): JdbcUrlBuilder = MssqlJdbcUrlBuilder()
    override fun schemaReader(): SchemaReader = MssqlSchemaReader()
    override fun tableLister(): TableLister = MssqlTableLister()

    override fun ddlGenerator(): DdlGenerator = MssqlDdlGenerator()

    override fun dataReader(): DataReader = dataReader(null)

    /** LN-005: `pipeline.fetch_size` erreicht den Reader über diese Naht. */
    override fun dataReader(fetchSize: Int?): DataReader = MssqlDataReader(fetchSize)

    override fun dataWriter(): DataWriter = MssqlDataWriter()

    /**
     * Strukturelle Typ-Vertraeglichkeit wie bei den anderen Dialekten: der
     * Transfer-Preflight vergleicht ueber den Typ-Mapper normalisiert (z. B.
     * integer→biginteger, VARCHAR(100)→NVARCHAR(200)), statt strikte Gleichheit
     * zu verlangen (Interface-Default).
     */
    override fun transferCompatibility(): TransferTypeCompatibility =
        StructuralTransferTypeCompatibility(MssqlTypeMapper())

    /**
     * Neutral-Typ-Projektion fuer den v7-Postcompare-Fingerprint
     * ([MssqlNeutralTypeCanonicalizer]). Sie wird erst mit dem
     * MSSQL-Migrate-Pfad (Slice 5) konsumiert; ohne sie faellt der Fingerprint
     * auf Identitaet zurueck und meldete jede T-SQL-Typabflachung als Drift.
     */
    override fun typeCanonicalizer(): NeutralTypeCanonicalizer = MssqlNeutralTypeCanonicalizer
}
