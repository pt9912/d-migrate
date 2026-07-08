package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry

/**
 * Resolves the TARGET dialect's neutral-type canonicalisation projection for
 * the v7 fingerprint (see `MigrationFingerprint` and the driver port
 * `NeutralTypeCanonicalizer`). Falls back to identity when no driver is
 * registered for the dialect (bare unit-test wiring without ServiceLoader
 * drivers) — identity never folds types away, so the failure direction is a
 * loud post-compare drift, never a masked one.
 */
internal fun registryTypeCanonicalizer(dialect: DatabaseDialect): (NeutralType) -> NeutralType {
    // Nur der Registry-Lookup fällt bei Miss (kein Driver registriert, einziger
    // Fehlermodus von get()) auf Identity zurück — der typeCanonicalizer()-Aufruf
    // steht bewusst AUSSERHALB, damit ein echter Kanonisierer-Fehler laut propagiert.
    val driver = runCatching { DatabaseDriverRegistry.get(dialect) }.getOrNull()
        ?: return { it }
    return driver.typeCanonicalizer()::canonicalize
}
