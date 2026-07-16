package dev.dmigrate.driver

import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit coverage for [DatabaseDriverRegistry]. The registry is the
 * single lookup point used by every driving adapter (CLI, MCP) to
 * resolve a [DatabaseDialect] to a concrete [DatabaseDriver]; without
 * tests it sat at 0% line coverage and was the lowest-coverage file
 * in `:hexagon:ports` (which had no test source set at all).
 *
 * Each test calls `clear()` upfront so state from prior tests in the
 * same JVM (the registry is an `object` — singleton state) cannot
 * leak across cases.
 */
class DatabaseDriverRegistryTest : FunSpec({

    beforeTest { DatabaseDriverRegistry.clear() }
    afterSpec { DatabaseDriverRegistry.clear() }

    test("register + get round-trips a driver by dialect") {
        val driver = StubDriver(DatabaseDialect.POSTGRESQL)
        DatabaseDriverRegistry.register(driver)
        DatabaseDriverRegistry.get(DatabaseDialect.POSTGRESQL) shouldBe driver
    }

    test("dataReader(fetchSize) default method delegates to dataReader() (LN-005)") {
        val driver = StubDriver(DatabaseDialect.POSTGRESQL)
        // The DatabaseDriver.dataReader(fetchSize) default ignores the value and
        // delegates to dataReader(); StubDriver's dataReader() throws, so the
        // delegation surfaces its error — proving the default reaches dataReader().
        val ex = shouldThrow<IllegalStateException> { driver.dataReader(1234) }
        ex.message shouldContain "does not provide a DataReader"
        shouldThrow<IllegalStateException> { driver.dataReader(null) }
    }

    test("register the same dialect twice keeps the most-recently-registered driver") {
        val first = StubDriver(DatabaseDialect.MYSQL)
        val second = StubDriver(DatabaseDialect.MYSQL)
        DatabaseDriverRegistry.register(first)
        DatabaseDriverRegistry.register(second)
        DatabaseDriverRegistry.get(DatabaseDialect.MYSQL) shouldBe second
    }

    test("get for an unregistered dialect throws IllegalArgumentException with a useful message") {
        DatabaseDriverRegistry.register(StubDriver(DatabaseDialect.SQLITE))
        val ex = shouldThrow<IllegalArgumentException> {
            DatabaseDriverRegistry.get(DatabaseDialect.POSTGRESQL)
        }
        // Message includes the requested dialect AND the registered set
        // so callers can self-diagnose missing-driver errors.
        ex.message.shouldContain("POSTGRESQL")
        ex.message.shouldContain("SQLITE")
    }

    test("clear empties the registry") {
        DatabaseDriverRegistry.register(StubDriver(DatabaseDialect.POSTGRESQL))
        DatabaseDriverRegistry.register(StubDriver(DatabaseDialect.MYSQL))
        DatabaseDriverRegistry.clear()
        shouldThrow<IllegalArgumentException> {
            DatabaseDriverRegistry.get(DatabaseDialect.POSTGRESQL)
        }
        shouldThrow<IllegalArgumentException> {
            DatabaseDriverRegistry.get(DatabaseDialect.MYSQL)
        }
    }

    test("loadAll discovers drivers via ServiceLoader") {
        // The test resources include
        // META-INF/services/dev.dmigrate.driver.DatabaseDriver listing
        // [ServiceLoaderProbeDriver]; loadAll() should pick it up and
        // make it lookup-able.
        DatabaseDriverRegistry.loadAll()
        val loaded = DatabaseDriverRegistry.get(ServiceLoaderProbeDriver.DIALECT)
        loaded.shouldBeInstanceOfStub(ServiceLoaderProbeDriver::class)
    }

    test("loadAll is idempotent — repeated calls don't multiply or break the registry") {
        DatabaseDriverRegistry.loadAll()
        DatabaseDriverRegistry.loadAll()
        DatabaseDriverRegistry.loadAll()
        // Still resolves to a single instance.
        val resolved = DatabaseDriverRegistry.get(ServiceLoaderProbeDriver.DIALECT)
        resolved.dialect shouldBe ServiceLoaderProbeDriver.DIALECT
    }
})

// ── Test helpers ────────────────────────────────────────────────────

private fun DatabaseDriver.shouldBeInstanceOfStub(expected: kotlin.reflect.KClass<*>) {
    val actual = this::class
    if (actual != expected) {
        error("Expected driver to be ${expected.qualifiedName} but was ${actual.qualifiedName}")
    }
}

/**
 * Minimal [DatabaseDriver] for in-process register/get tests. Every
 * port accessor throws — the registry never invokes them, only
 * stores and returns the instance.
 */
private class StubDriver(override val dialect: DatabaseDialect) : DatabaseDriver {
    override fun ddlGenerator(): DdlGenerator = error("StubDriver does not provide a DdlGenerator")
    override fun dataReader(): DataReader = error("StubDriver does not provide a DataReader")
    override fun tableLister(): TableLister = error("StubDriver does not provide a TableLister")
    override fun dataWriter(): DataWriter = error("StubDriver does not provide a DataWriter")
    override fun urlBuilder(): JdbcUrlBuilder = error("StubDriver does not provide a JdbcUrlBuilder")
    override fun schemaReader(): SchemaReader = error("StubDriver does not provide a SchemaReader")
}

/**
 * Driver registered via the
 * `META-INF/services/dev.dmigrate.driver.DatabaseDriver` test resource
 * so the [DatabaseDriverRegistry.loadAll] code path can be exercised
 * without depending on any production driver-* adapter from the
 * test classpath. Uses a dialect value that no real driver claims
 * (PostgreSQL is fine because the test calls `clear()` first).
 */
class ServiceLoaderProbeDriver : DatabaseDriver {
    companion object {
        val DIALECT: DatabaseDialect = DatabaseDialect.POSTGRESQL
    }

    override val dialect: DatabaseDialect = DIALECT

    override fun ddlGenerator(): DdlGenerator = error("ServiceLoaderProbeDriver does not provide a DdlGenerator")
    override fun dataReader(): DataReader = error("ServiceLoaderProbeDriver does not provide a DataReader")
    override fun tableLister(): TableLister = error("ServiceLoaderProbeDriver does not provide a TableLister")
    override fun dataWriter(): DataWriter = error("ServiceLoaderProbeDriver does not provide a DataWriter")
    override fun urlBuilder(): JdbcUrlBuilder = error("ServiceLoaderProbeDriver does not provide a JdbcUrlBuilder")
    override fun schemaReader(): SchemaReader = error("ServiceLoaderProbeDriver does not provide a SchemaReader")
}
