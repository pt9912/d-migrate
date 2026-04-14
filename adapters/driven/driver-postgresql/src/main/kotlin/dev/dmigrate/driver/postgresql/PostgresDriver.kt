package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister

/**
 * [DatabaseDriver] implementation for PostgreSQL.
 *
 * Bundles all PostgreSQL-specific adapter components behind the
 * central port facade.
 */
class PostgresDriver : DatabaseDriver {
    override val dialect = DatabaseDialect.POSTGRESQL
    override fun ddlGenerator(): DdlGenerator = PostgresDdlGenerator()
    override fun dataReader(): DataReader = PostgresDataReader()
    override fun tableLister(): TableLister = PostgresTableLister()
    override fun dataWriter(): DataWriter = PostgresDataWriter()
    override fun urlBuilder(): JdbcUrlBuilder = PostgresJdbcUrlBuilder()
    override fun schemaReader(): SchemaReader = PostgresSchemaReader()
}
