package dev.dmigrate.cli.commands

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities

/**
 * Eine Seite von `schema compare`: das Schema — Reverse-Markierung bereits
 * bereinigt — und der Dialekt, aus dem es zurueckgelesen wurde.
 *
 * [sourceDialect] ist `null` fuer ein handgeschriebenes Schema: es hat keinen
 * Server, dessen Buchhaltung man ausblenden muesste.
 */
data class CompareSide(
    val schema: SchemaDefinition,
    val sourceDialect: DatabaseDialect? = null,
)

/**
 * Der Dialekt, dessen Namens-Naht ([capabilityIdentitySequenceNameCanonicalizer])
 * der **symmetrische** Vergleich zweier Seiten benutzt.
 *
 * Zwei Reverses haben keine Zielseite. Uebergeben wird deshalb der Dialekt
 * der **ersten** Seite (Quelle vor Ziel), deren Reverse den Sequenznamen einer
 * IDENTITY-Spalte als Server-Buchhaltung liest
 * (`DialectCapabilities.namesIdentitySequences = false`: PostgreSQL, Oracle).
 * Liest keine Seite ihn so, gibt es nichts auszublenden — `null`, der
 * Vergleich bleibt strikt.
 *
 * Die Folgen, gepinnt in `CompareGenerationProjectionTest`:
 * - PostgreSQL gegen MySQL: der Name der PostgreSQL-Seite faellt; MySQL setzt
 *   bei `AUTO_INCREMENT` nie einen.
 * - PostgreSQL gegen Oracle: **beide** Namen fallen — beide hat ein Server
 *   vergeben, keiner beschreibt, was die Spalte ist.
 * - MySQL gegen MySQL: nichts faellt, der Modus bleibt vergleichbar.
 */
fun compareProjectionDialect(vararg sourceDialects: DatabaseDialect?): DatabaseDialect? =
    sourceDialects.filterNotNull().firstOrNull { !DialectCapabilities.forDialect(it).namesIdentitySequences }

/**
 * Der Dialekt, dessen Serial-Naht ([capabilitySerialSyntaxCanonicalizer]) der
 * symmetrische Vergleich benutzt: der der **ersten** Seite, deren Dialekt
 * `SERIAL` und IDENTITY nicht unterscheidet
 * (`DialectCapabilities.distinguishesSerialFromIdentity = false`: MySQL,
 * SQLite, SQL Server, Oracle). Stammt eine Seite von dort, traegt
 * `legacy_serial_syntax` keine Aussage ueber die Spalte, und der Vergleich
 * wertet es auf **beiden** Seiten nicht. Unterscheiden beide Seiten (zwei
 * PostgreSQL-Reverses) oder hat keine Seite einen Dialekt, `null`: das Flag
 * bleibt ein Unterschied.
 */
fun compareSerialDialect(vararg sourceDialects: DatabaseDialect?): DatabaseDialect? =
    sourceDialects.filterNotNull().firstOrNull { !DialectCapabilities.forDialect(it).distinguishesSerialFromIdentity }

/**
 * Die Erzeugungs-Projektion von `schema compare` — `null` heisst strikt.
 *
 * Zwei Teile, beide an der Faehigkeits-Naht und beide nur fuer den
 * symmetrischen Vergleich:
 * - der **Namens-Teil** ([compareProjectionDialect],
 *   [capabilityIdentitySequenceNameCanonicalizer]) — nicht die ganze
 *   [capabilityGenerationCanonicalizer]: deren Speicherform-Teil haengt an
 *   einer Zielseite und ihrer Version, und die gibt es hier nicht; `stored`
 *   bleibt deshalb sichtbar;
 * - der **Serial-Teil** ([compareSerialDialect],
 *   [capabilitySerialSyntaxCanonicalizer]).
 *
 * Der Modus einer Identity-Spalte bleibt ein Unterschied. Der Fingerabdruck
 * und `schema migrate` sehen diese Projektion nicht.
 */
fun compareGenerationCanonicalizer(
    source: CompareSide,
    target: CompareSide,
): ((ColumnGeneration?) -> ColumnGeneration?)? {
    val names = compareProjectionDialect(source.sourceDialect, target.sourceDialect)
        ?.let { capabilityIdentitySequenceNameCanonicalizer(it) }
    val serial = compareSerialDialect(source.sourceDialect, target.sourceDialect)
        ?.let { capabilitySerialSyntaxCanonicalizer(it) }
    return when {
        names == null -> serial
        serial == null -> names
        else -> { generation -> serial(names(generation)) }
    }
}

/**
 * Der Dialekt aus der Reverse-Markierung eines Schemas
 * ([ReverseScopeCodec]) — `null` fuer ein handgeschriebenes Schema oder
 * einen Dialektnamen, den diese Version nicht kennt.
 */
fun reverseSourceDialect(schema: SchemaDefinition): DatabaseDialect? {
    if (!ReverseScopeCodec.isReverseGenerated(schema.name, schema.version)) return null
    val dialect = ReverseScopeCodec.parseScope(schema.name)["dialect"] ?: return null
    return DatabaseDialect.entries.firstOrNull { it.name.equals(dialect, ignoreCase = true) }
}
