package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnGenerationTransition

/**
 * Welche `AlterColumnGeneration` auf SQLite ueber den Tabellen-Neubau laeuft --
 * und welche nicht.
 *
 * **Der Ausdrucksfall gehoert in den Neubau**, der Identity-Fall nicht. Auf
 * SQLite steckt die Identity im **Spaltentyp** (`Identifier(autoIncrement =
 * true)`, gerendert als `INTEGER PRIMARY KEY AUTOINCREMENT`), nicht in
 * `generation`; der Reverse liefert sie dort nie. Ein Soll, das die Identity
 * ueber `generation` setzt, beschreibt damit etwas, das der Neubau nicht
 * herstellen kann.
 *
 * **Gemessen, was vorher geschah:** der Lauf baute die Tabelle wirklich um --
 * `CREATE TABLE …__dmg_rebuild_…`, `INSERT … SELECT`, `DROP TABLE`, `RENAME` --
 * und die neue Tabelle sah aus wie die alte, ohne `AUTOINCREMENT`. Danach
 * meldete der Post-Compare Drift (Exit 5). Eine Tabelle fuer nichts umzubauen
 * ist teurer und riskanter, als die Aenderung benannt abzulehnen.
 */
internal object SqliteGenerationTransitions {

    fun isIdentityMatter(op: DiffOperation): Boolean =
        op is DiffOperation.AlterColumnGeneration &&
            ColumnGenerationTransition.of(op.before, op.after) == ColumnGenerationTransition.IDENTITY
}
