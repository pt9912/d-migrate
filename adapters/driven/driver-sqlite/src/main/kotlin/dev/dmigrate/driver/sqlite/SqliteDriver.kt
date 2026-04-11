package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister

/**
 * [DatabaseDriver] implementation for SQLite.
 */
class SqliteDriver : DatabaseDriver {
    override val dialect = DatabaseDialect.SQLITE
    override fun ddlGenerator(): DdlGenerator = SqliteDdlGenerator()
    override fun dataReader(): DataReader = SqliteDataReader()
    override fun tableLister(): TableLister = SqliteTableLister()
    override fun dataWriter(): DataWriter = SqliteDataWriter()
    override fun urlBuilder(): JdbcUrlBuilder = SqliteJdbcUrlBuilder()
}
