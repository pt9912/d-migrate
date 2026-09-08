package dev.dmigrate.driver.metadata

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.TableDefinition

/**
 * Ein einspaltiger UNIQUE-Constraint, der einen **Namen** traegt, wird als
 * Tabellen-Constraint gerendert — nicht inline an der Spalte.
 *
 * Der Grund ist gemessen, nicht gewaehlt: MySQL 8.0 lehnt die inline benannte
 * Form ab (`email VARCHAR(50) CONSTRAINT uq UNIQUE` ist ein Syntaxfehler),
 * nimmt die Tabellenform aber an. Statt zwei Formen ueber fuenf Dialekte zu
 * pflegen, rendern alle dieselbe.
 *
 * Ohne Namen bleibt es beim inline `UNIQUE`: das ist die knappere Form, und
 * ohne Namen gibt es nichts zu erhalten.
 */
object NamedUniqueConstraints {

    /** Ob die Spalte ihr `UNIQUE` inline traegt — also ungenannt bleibt. */
    fun rendersInline(column: ColumnDefinition): Boolean =
        column.unique && column.uniqueConstraintName == null

    /** Die Tabellen-Klauseln fuer alle benannten einspaltigen UNIQUE-Constraints. */
    fun clauses(table: TableDefinition, quote: (String) -> String): List<String> =
        table.columns
            .filter { (_, column) -> column.unique && column.uniqueConstraintName != null }
            .map { (name, column) ->
                "CONSTRAINT ${quote(column.uniqueConstraintName!!)} UNIQUE (${quote(name)})"
            }
}
