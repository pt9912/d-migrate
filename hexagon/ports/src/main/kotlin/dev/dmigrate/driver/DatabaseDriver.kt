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
}
