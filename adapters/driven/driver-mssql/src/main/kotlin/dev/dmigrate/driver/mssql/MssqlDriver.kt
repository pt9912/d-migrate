package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister

/**
 * [DatabaseDriver] implementation for MSSQL.
 *
 * Reverse-read and DDL-generate surface: the data ports are unreachable
 * because `DialectCommandGate` (ADR 0047) rejects mssql at the command
 * boundary of every import/export/transfer/migrate/profile path; the
 * remaining capability methods keep their conservative interface defaults.
 */
class MssqlDriver : DatabaseDriver {
    override val dialect = DatabaseDialect.MSSQL
    override fun urlBuilder(): JdbcUrlBuilder = MssqlJdbcUrlBuilder()
    override fun schemaReader(): SchemaReader = MssqlSchemaReader()
    override fun tableLister(): TableLister = MssqlTableLister()

    override fun ddlGenerator(): DdlGenerator = MssqlDdlGenerator()

    override fun dataReader(): DataReader =
        error("unreachable: DialectCommandGate rejects mssql for data export/transfer (ADR 0047)")

    override fun dataWriter(): DataWriter =
        error("unreachable: DialectCommandGate rejects mssql for data import/transfer (ADR 0047)")
}
