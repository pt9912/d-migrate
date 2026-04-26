package dev.dmigrate.mcp.server

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.format.SchemaFileResolver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * §6.3 Akzeptanz: nach MCP-Serverstart muessen Driver- und Codec-
 * Lookups funktionieren — Tool-Handler (Phase C/D) sollen ohne ad-hoc
 * Reflection auskommen.
 */
class McpServerBootstrapRuntimeLookupTest : FunSpec({

    test("after startHttp, DatabaseDriver lookup returns registered drivers") {
        DatabaseDriverRegistry.clear()
        val outcome = McpServerBootstrap.startHttp(McpServerConfig(authMode = AuthMode.DISABLED))
        outcome.shouldBeInstanceOf<McpStartOutcome.Started>()
        try {
            // Drivers come from the runtime classpath via ServiceLoader.
            DatabaseDriverRegistry.get(DatabaseDialect.POSTGRESQL) shouldNotBe null
            DatabaseDriverRegistry.get(DatabaseDialect.MYSQL) shouldNotBe null
            DatabaseDriverRegistry.get(DatabaseDialect.SQLITE) shouldNotBe null
        } finally {
            outcome.handle.close()
        }
    }

    test("after startStdio, DatabaseDriver lookup works as well") {
        DatabaseDriverRegistry.clear()
        val outcome = McpServerBootstrap.startStdio(
            McpServerConfig(authMode = AuthMode.DISABLED),
            input = ByteArrayInputStream(ByteArray(0)),
            output = ByteArrayOutputStream(),
        )
        outcome.shouldBeInstanceOf<McpStartOutcome.Started>()
        try {
            DatabaseDriverRegistry.get(DatabaseDialect.POSTGRESQL) shouldNotBe null
        } finally {
            outcome.handle.close()
        }
    }

    test("Schema codec lookup works without driver registry") {
        // SchemaFileResolver is stateless — no bootstrap step required,
        // only the formats module on the classpath. Validates §6.3
        // Akzeptanz #2.
        SchemaFileResolver.codecForFormat("yaml") shouldNotBe null
        SchemaFileResolver.codecForFormat("json") shouldNotBe null
    }
})
