package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister

/**
 * [DatabaseDriver]-Implementierung für Oracle.
 *
 * Reverse-Read (Slice 1) und DDL-Generate (Slice 2). Die Datenpfad-Ports
 * bleiben unerreichbar, weil `DialectCommandGate` (ADR 0052) oracle an der
 * Kommando-Grenze jedes export-/import-/transfer-/migrate-/profile-Pfads
 * abweist; die übrigen Fähigkeitsmethoden behalten ihre konservativen
 * Interface-Defaults.
 */
class OracleDriver : DatabaseDriver {
    override val dialect = DatabaseDialect.ORACLE
    override fun urlBuilder(): JdbcUrlBuilder = OracleJdbcUrlBuilder()
    override fun schemaReader(): SchemaReader = OracleSchemaReader()
    override fun tableLister(): TableLister = OracleTableLister()
    override fun ddlGenerator(): DdlGenerator = OracleDdlGenerator()

    override fun dataReader(): DataReader =
        error("unreachable: DialectCommandGate rejects oracle for data export/transfer (ADR 0052)")

    override fun dataWriter(): DataWriter =
        error("unreachable: DialectCommandGate rejects oracle for data import/transfer (ADR 0052)")
}
