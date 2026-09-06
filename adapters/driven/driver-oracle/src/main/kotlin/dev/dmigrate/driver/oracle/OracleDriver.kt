package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DdlGenerator
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
 * Reverse-Read (Slice 1), DDL-Generate (Slice 2) und Datenpfad (Slice 3,
 * `data export`/`import`/`transfer`). Die verbleibenden Kommandos
 * (`schema migrate`, `data profile`) bleiben unerreichbar, weil
 * `DialectCommandGate` (ADR 0052) oracle dort noch an der Kommando-Grenze
 * abweist; die übrigen Fähigkeitsmethoden behalten ihre konservativen
 * Interface-Defaults.
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
}
