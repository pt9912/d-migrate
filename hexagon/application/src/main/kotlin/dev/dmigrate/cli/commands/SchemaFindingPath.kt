package dev.dmigrate.cli.commands

/**
 * Der Ort eines Vergleichsfunds — das **Pfad-Schema** von `schema compare`
 * (`spec/cli-spec.md`). Es gilt fuer die `path`-Werte der MCP-`findings` und
 * fuer den Ort, den eine Diagnose wie `W137` nennt; beide bauen ihn hier.
 *
 * Ein Pfad ist der Ort im **neutralen Schema-Dokument**: dessen Schluessel
 * und die Objektnamen, mit Punkten verbunden — dasselbe Vokabular wie die
 * Pfade von `schema validate` (`tables.orders.constraints.ck_mail`).
 */
object SchemaFindingPath {

    fun table(table: String): String = "tables.$table"

    fun column(table: String, column: String): String = "${table(table)}.columns.$column"

    /** Ein Feld unterhalb eines Ortes, unter seinem Dokument-Schluessel: `….generation.expression`. */
    fun field(path: String, vararg keys: String): String = (listOf(path) + keys).joinToString(".")
}
