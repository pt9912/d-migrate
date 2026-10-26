package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition

/**
 * Quelle und Ziel eines Tabellenneubaus, je Tabelle und Schema, samt Richtung. Der Neubau kopiert
 * aus [source] in eine Tabelle der Form [target]: Up von der Datenbank (`current`) zur Datei
 * (`desired`), Down umgekehrt.
 *
 * Die Datenbank-Seite ist je Richtung die Quelle (Up) oder das Ziel (Down); die Datei-Seite die andere.
 * Strukturmerkmale, die `schema migrate` nicht plant (`WITHOUT ROWID`), folgen der Datenbank-Seite.
 */
internal data class SqliteRebuildSides(
    val source: TableDefinition,
    val target: TableDefinition,
    val sourceSchema: SchemaDefinition? = null,
    val targetSchema: SchemaDefinition? = null,
    val direction: SqliteRenderDirection = SqliteRenderDirection.UP,
) {
    /** Die Tabelle, wie die Datenbank sie vor dem Lauf fuehrt. */
    val databaseTable: TableDefinition
        get() = if (direction == SqliteRenderDirection.UP) source else target

    /** Die Tabelle, wie die Datei sie beschreibt. */
    val fileTable: TableDefinition
        get() = if (direction == SqliteRenderDirection.UP) target else source
}
