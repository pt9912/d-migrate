package dev.dmigrate.server.application.bootstrap

import dev.dmigrate.driver.DatabaseDriverRegistry

/**
 * Shared runtime bootstrap for driving adapters (CLI, MCP, …).
 *
 * Phase B §6.3 (`ImpPlan-0.9.6-B.md`) requires a single bootstrap entry
 * point that:
 * - registers all `DatabaseDriver` implementations available on the
 *   classpath via `ServiceLoader` (idempotent)
 * - never calls `exitProcess` — failures surface as exceptions so tests
 *   and the MCP-Server-Lifecycle can react
 * - leaves schema codecs to `SchemaFileResolver`, which is stateless
 *   and needs no registration step
 *
 * Streaming and Profiling are pure class libraries; they are made
 * available by declaring them as runtime dependencies on the calling
 * driving-adapter module — there is no registry to populate.
 */
object RuntimeBootstrap {

    fun initialize() {
        DatabaseDriverRegistry.loadAll()
    }
}
