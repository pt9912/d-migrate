package dev.dmigrate.server.application.bootstrap

import io.kotest.core.spec.style.FunSpec

/**
 * Smoke test: `initialize()` is the call-site contract — it must run
 * to completion without throwing, even on a classpath without any
 * `DatabaseDriver` SPI entries (then `loadAll()` simply registers
 * nothing). The actual driver-discovery acceptance criterion (§6.3
 * Akzeptanz #1) is verified in the MCP module, whose runtime
 * classpath carries the driver implementations.
 */
class RuntimeBootstrapTest : FunSpec({

    test("initialize runs without throwing") {
        RuntimeBootstrap.initialize()
    }

    test("initialize is idempotent under repeat invocations") {
        RuntimeBootstrap.initialize()
        RuntimeBootstrap.initialize()
        RuntimeBootstrap.initialize()
    }
})
