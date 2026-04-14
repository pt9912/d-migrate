package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister

/**
 * [DatabaseDriver] implementation for MySQL.
 */
class MysqlDriver : DatabaseDriver {
    override val dialect = DatabaseDialect.MYSQL
    override fun ddlGenerator(): DdlGenerator = MysqlDdlGenerator()
    override fun dataReader(): DataReader = MysqlDataReader()
    override fun tableLister(): TableLister = MysqlTableLister()
    override fun dataWriter(): DataWriter = MysqlDataWriter()
    override fun urlBuilder(): JdbcUrlBuilder = MysqlJdbcUrlBuilder()
    override fun schemaReader(): SchemaReader =
        throw UnsupportedOperationException("Schema reading not yet implemented for $dialect")
}
