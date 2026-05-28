package dev.dmigrate.driver

import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister

/**
 * Central port interface for database access. Each supported database
 * dialect provides an implementation that bundles all driver-specific
 * capabilities behind this facade.
 *
 * [TypeMapper] is intentionally NOT exposed here — it is an internal
 * implementation detail of [DdlGenerator] (via AbstractDdlGenerator).
 * Consumers who obtain a [DdlGenerator] through [ddlGenerator] get
 * type-mapping implicitly.
 */
interface DatabaseDriver {
    val dialect: DatabaseDialect

    fun ddlGenerator(): DdlGenerator
    fun dataReader(): DataReader
    fun tableLister(): TableLister
    fun dataWriter(): DataWriter
    fun urlBuilder(): JdbcUrlBuilder
    fun schemaReader(): SchemaReader

    /**
     * Returns the driver's dialect-specific pre-generation validator,
     * or [PreGenerationValidator.NoOp] if the driver carries no
     * mode-specific gates. The runner calls
     * [PreGenerationValidator.validate] after the dialect-agnostic
     * [dev.dmigrate.core.validation.SchemaValidator] passes and before
     * [DdlGenerator.generate] runs.
     */
    fun preGenerationValidator(): PreGenerationValidator = PreGenerationValidator.NoOp
}
