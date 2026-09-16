package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.IndexDefinition

/**
 * Der Ort eines Vergleichsfunds — das **Pfad-Schema** von `schema compare`
 * (`spec/cli-spec.md`). Es gilt fuer die `path`-Werte der MCP-`findings` und
 * fuer den Ort, den eine Diagnose wie `W137` nennt; beide bauen ihn hier.
 *
 * Ein Pfad ist der Ort im **neutralen Schema-Dokument**: dessen Schluessel
 * und die Objektnamen, mit Punkten verbunden — dasselbe Vokabular wie die
 * Pfade von `schema validate` (`tables.orders.constraints.ck_mail`). Die
 * Metadaten des Schemas (`name`, `version`) stehen dort auf der obersten
 * Ebene und tragen deshalb kein Praefix.
 */
object SchemaFindingPath {

    /** Der Schemaname — ein Schluessel der obersten Ebene. */
    const val NAME: String = "name"

    /** Die Schemaversion — ein Schluessel der obersten Ebene. */
    const val VERSION: String = "version"

    /** Die Objekt-Abschnitte des Dokuments, unter ihrem Schluessel. */
    enum class Section(val key: String) {
        TABLES("tables"),
        VIEWS("views"),
        SEQUENCES("sequences"),
        CUSTOM_TYPES("custom_types"),
        FUNCTIONS("functions"),
        PROCEDURES("procedures"),
        TRIGGERS("triggers"),
    }

    /** Ein Objekt der obersten Ebene: `views.active_orders`. */
    fun of(section: Section, name: String): String = "${section.key}.$name"

    fun table(table: String): String = of(Section.TABLES, table)

    fun column(table: String, column: String): String = "${table(table)}.columns.$column"

    /** Ein Index unter einem bereits gebildeten Abschnitt ([indexSegment]). */
    fun index(table: String, index: String): String = "${table(table)}.indices.$index"

    /** Ein Index: unter seinem Namen, ein unbenannter unter [indexSegment]. */
    fun index(table: String, index: IndexDefinition): String = index(table, indexSegment(index))

    /**
     * Der Abschnitt eines Index im Pfad — sein Name, bei einem unbenannten
     * seine Schluessel kommagetrennt: Spaltennamen wortgleich, ein Ausdruck
     * als Bezeichner-Kurzform ([IndexDefinition.keyLabels]: `lower(t.email)`
     * wird `lower_t_email`), ohne Sortierrichtung und Praefixlaenge. So traegt
     * der Abschnitt keinen Punkt, den der Ausdruck mitbraechte. Er ist ein
     * Ort, keine Identitaet: zwei unbenannte Indizes mit denselben Schluesseln
     * teilen ihn, ihre `details` trennen sie.
     */
    fun indexSegment(index: IndexDefinition): String = index.name ?: index.keyLabels.joinToString(",")

    fun constraint(table: String, constraint: String): String = "${table(table)}.constraints.$constraint"

    /** Ein Feld unterhalb eines Ortes, unter seinem Dokument-Schluessel: `….generation.expression`. */
    fun field(path: String, vararg keys: String): String = (listOf(path) + keys).joinToString(".")
}
