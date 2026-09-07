package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.NeutralTypeCanonicalizer
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.StructuralTransferTypeCompatibility
import dev.dmigrate.driver.TransferTypeCompatibility
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister

/**
 * [DatabaseDriver]-Implementierung für Oracle.
 *
 * Reverse-Read, DDL-Generate, Datenpfad (`data export`/`import`/`transfer`),
 * Postcompare-Vergleichssubstrat (`typeCanonicalizer()`) und Diff/Migrate.
 * `data profile` haengt an eigenen Ports und liegt deshalb in einem eigenen
 * Modul (`driver-oracle-profiling`); die uebrigen Faehigkeitsmethoden
 * behalten ihre konservativen Interface-Defaults.
 */
class OracleDriver : DatabaseDriver {
    override val dialect = DatabaseDialect.ORACLE
    override fun urlBuilder(): JdbcUrlBuilder = OracleJdbcUrlBuilder()
    override fun schemaReader(): SchemaReader = OracleSchemaReader()
    override fun tableLister(): TableLister = OracleTableLister()
    override fun ddlGenerator(): DdlGenerator = OracleDdlGenerator()
    override fun dataReader(): DataReader = dataReader(null)

    /** LN-005: `pipeline.fetch_size`/`--fetch-size` erreicht den Reader über diese Naht. */
    override fun dataReader(fetchSize: Int?): DataReader = OracleDataReader(fetchSize)
    override fun dataWriter(): DataWriter = OracleDataWriter()

    override fun transferCompatibility(): TransferTypeCompatibility =
        StructuralTransferTypeCompatibility(OracleTypeMapper())

    override fun typeCanonicalizer(): NeutralTypeCanonicalizer = OracleNeutralTypeCanonicalizer
}
