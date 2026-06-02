package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.TriggerPlanningContext
import dev.dmigrate.core.diff.migration.TriggerReplaceMode
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.TriggerCapabilityDefaults
import dev.dmigrate.driver.TriggerCapabilityResolution
import dev.dmigrate.driver.resolve

/**
 * E.2 Sub-Slice A.3: maps the [hexagon:ports-read] trigger-capability
 * surface to the core-local [TriggerPlanningContext].
 *
 * `hexagon:core` deliberately has zero external dependencies, so the
 * `DiffPlanner` cannot read `TriggerCapability` directly. This factory
 * lives in the application layer (which already depends on both core
 * and ports-read) and resolves the capability against the runtime
 * server version before handing the result to the planner.
 *
 * Today only PostgreSQL has a non-trivial floor (`minPostgresMajorVersion
 * = 14`). MySQL and SQLite resolve to `Disabled` regardless of server
 * version. File-only PG targets (no `postgresMajorVersion` available)
 * also resolve to `Disabled` — the conservative posture.
 */
internal object TriggerPlanningContextFactory {

    fun forDialect(
        dialect: DatabaseDialect,
        postgresMajorVersion: Int? = null,
    ): TriggerPlanningContext {
        val capability = TriggerCapabilityDefaults.forDialect(dialect)
        val mode = when (capability.resolve(postgresMajorVersion)) {
            TriggerCapabilityResolution.Active -> TriggerReplaceMode.NATIVE_REPLACE
            TriggerCapabilityResolution.Disabled -> TriggerReplaceMode.DROP_CREATE_FALLBACK
        }
        return TriggerPlanningContext(replaceMode = mode)
    }
}
