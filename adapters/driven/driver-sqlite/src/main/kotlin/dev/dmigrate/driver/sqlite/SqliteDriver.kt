package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.PreGenerationValidator
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.driver.sqliteContext

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
    override fun schemaReader(): SchemaReader = SqliteSchemaReader()
    override fun preGenerationValidator(): PreGenerationValidator = SqlitePreGenerationValidator
}

/**
 * Bridges the dialect-agnostic [PreGenerationValidator] port to the
 * SQLite-specific [SqliteHelperTableSequenceValidator]. Reads the
 * named-sequence mode out of [DdlGenerationOptions.sqliteContext]
 * (defaulting to [SqliteNamedSequenceMode.ACTION_REQUIRED] if the
 * caller did not supply a SQLite dialect context) and delegates.
 */
internal object SqlitePreGenerationValidator : PreGenerationValidator {
    override fun validate(
        schema: SchemaDefinition,
        options: DdlGenerationOptions,
    ): List<ValidationError> {
        val mode = options.sqliteContext?.namedSequenceMode ?: SqliteNamedSequenceMode.ACTION_REQUIRED
        return SqliteHelperTableSequenceValidator.validate(schema, mode)
    }
}
