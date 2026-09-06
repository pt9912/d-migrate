package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.DialectCapabilities

/**
 * Der Fingerabdruck eines Schemas, gesehen durch die Projektionen des
 * Ziel-Dialekts: die Typ-Projektion und die Index-Projektion. Beide gehoeren
 * zusammen -- ein Aufrufer, der nur eine von beiden setzt, vergliche zwei
 * Schemata durch verschiedene Brillen.
 */
internal typealias FingerprintOfSchema = (
    SchemaDefinition,
    (NeutralType) -> NeutralType,
    (IndexDefinition) -> IndexDefinition,
    (ColumnGeneration?) -> ColumnGeneration?,
) -> String

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

/**
 * Projiziert einen Index auf das, was der Ziel-Dialekt davon zurueckgeben kann.
 *
 * INCLUDE-Spalten, die clustered-Steuerung und der Name eines Volltext-Index sind
 * semantisch und stehen deshalb im Vergleich; nur kann ein Dialekt, der sie
 * nicht kennt oder nicht speichert, sie auch nicht zurueckmelden. Ohne diese Projektion meldete der Post-Compare nach einem
 * `migrate --execute` gegen MySQL oder SQLite Drift fuer etwas, das der Zielserver
 * gar nicht ausdruecken kann. Dieselbe Grenze wie bei der Typ-Projektion: der
 * strukturelle Vergleich (`schema compare`) bleibt streng, nur der
 * Fingerabdruck-Pfad kanonisiert.
 */
fun capabilityIndexCanonicalizer(
    dialect: DatabaseDialect,
): (IndexDefinition) -> IndexDefinition {
    val caps = DialectCapabilities.forDialect(dialect)
    if (caps.supportsIndexIncludeColumns && caps.supportsClusteredIndexes && caps.namesFullTextIndexes) {
        return { it }
    }
    return { index ->
        index.copy(
            includeColumns = if (caps.supportsIndexIncludeColumns) index.includeColumns else emptyList(),
            clustered = caps.supportsClusteredIndexes && index.clustered,
            // Ein Dialekt, der Volltext-Indizes nicht benennt, kann den Namen
            // auch nicht zurueckgeben — der Reverse synthetisiert ihn. Beide
            // Seiten auf denselben Wert, sonst driftete der Round-Trip an einem
            // Namen, den niemand vergeben hat.
            name = if (index.type == IndexType.FULLTEXT && !caps.namesFullTextIndexes) null else index.name,
        )
    }
}

/**
 * Projiziert die Erzeugungsart einer Spalte auf das, was der Ziel-Dialekt
 * davon zurueckmelden **kann**.
 *
 * Betroffen ist nur der Sequenzname einer IDENTITY-Spalte. Wo er nicht
 * vergebbar ist, vergibt ihn der Server (Oracle: `ISEQ${'$'}${'$'}_n`) — ein
 * user-authored Soll-Schema kann ihn nicht kennen, weil er erst beim
 * `CREATE TABLE` entsteht, der Reverse liest ihn aber. Beide Seiten auf
 * denselben Wert, sonst meldete der Post-Compare nach jedem
 * `migrate --execute` Drift fuer eine Spalte, die genau so angelegt wurde
 * wie gewuenscht.
 *
 * Dieselbe Grenze wie bei Typ- und Index-Projektion: `schema compare`
 * bleibt streng und zeigt den Namen, nur der Fingerabdruck-Pfad faltet ihn
 * weg.
 */
fun capabilityGenerationCanonicalizer(
    dialect: DatabaseDialect,
): (ColumnGeneration?) -> ColumnGeneration? {
    if (DialectCapabilities.forDialect(dialect).namesIdentitySequences) return { it }
    return { generation ->
        when (generation) {
            null -> null
            is ColumnGeneration.Identity -> generation.copy(sequenceName = null)
        }
    }
}
