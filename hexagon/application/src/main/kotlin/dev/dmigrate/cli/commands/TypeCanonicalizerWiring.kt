package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
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

/**
 * Dieselbe Projektion, aber an ein konkretes Schema gebunden.
 *
 * Ein `Enum(refType)` traegt seine Werte nicht selbst; welcher Typ daraus wird,
 * steht in `schema.customTypes`. Gebunden wird deshalb **pro Schema** — jede
 * Seite des Vergleichs loest gegen ihre eigenen Custom Types auf, und ein
 * zurueckgelesenes Schema ohne Custom Types loest schlicht nichts auf.
 *
 * Nur der Fingerprint-Pfad nutzt das. Der strukturelle Vergleich bleibt bei
 * der kontextfreien Projektion — dieselbe Grenze, die ADR 0026 zieht.
 */
internal fun registrySchemaAwareCanonicalizer(
    dialect: DatabaseDialect,
    schema: SchemaDefinition,
): (NeutralType) -> NeutralType {
    val driver = runCatching { DatabaseDriverRegistry.get(dialect) }.getOrNull()
        ?: return { it }
    val canonicalizer = driver.typeCanonicalizer()
    return { type -> canonicalizer.canonicalize(type, schema.customTypes) }
}
