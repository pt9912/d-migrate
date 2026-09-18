package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * Der SRID einer Oracle-Geometriespalte — und was fehlt, wenn er fehlt.
 *
 * Oracle fuehrt den SRID nicht an der Spalte, sondern in einer Zeile von
 * `USER_SDO_GEOM_METADATA`. Tabellen- und Spaltenname stehen dort
 * **bedingungslos gross**; zu einer quotiert kleingeschriebenen Tabelle — so
 * legt d-migrate sie an — kann es deshalb keine Zeile geben, die sie
 * beschreibt. Der Reverse liest den SRID per exaktem Namensabgleich und
 * verlor ihn bis hierher **ohne jede Meldung**.
 *
 * Zwei Faelle, die sich ausschliessen:
 *
 * - die Sicht ist **nicht lesbar** (kein Oracle Spatial, kein Leserecht) —
 *   `R365`; ueber ihre Zeilen laesst sich dann nichts sagen, und ein
 *   zusaetzliches `R370` entstuende ins Blaue;
 * - die Sicht ist lesbar und eine **Zeile fehlt** — `R370`, mit zwei Texten
 *   je nachdem, ob es die Zeile ueberhaupt geben koennte.
 *
 * Beide sind `WARNING` und blocken nichts: die Spalte bleibt im Schema, nur
 * ohne Bezugssystem. Der Abgleich bleibt wortgetreu — ein toleranter
 * Abgleich wertete die Zeile einer **anderen** Tabelle aus, und dieselbe
 * Abfrage speist den Datenpfad.
 */
internal object OracleGeometryMetadata {

    /** Was `ALL_SDO_GEOM_METADATA` zu einer Tabelle sagt. */
    sealed interface Scan {
        /** Die Sicht ist lesbar; [rows] sind die Zeilen dieser Tabelle, je Spalte. */
        data class Readable(val rows: Map<String, OracleMetadataQueries.GeometryMetadataRow>) : Scan

        /** Die Sicht ist nicht lesbar — ueber ihre Zeilen ist nichts bekannt. */
        data object Unreadable : Scan
    }

    /** Die leere Auskunft fuer eine Tabelle ohne Geometriespalte: nichts zu holen, nichts zu melden. */
    val NONE: Scan = Scan.Readable(emptyMap())

    /**
     * Liest die Metadatenzeilen einer Tabelle **mit** Geometriespalten.
     *
     * Ohne installiertes Oracle Spatial gibt es `ALL_SDO_GEOM_METADATA`
     * nicht — die Abfrage scheitert dann mit ORA-00942. Der Aufrufer fragt
     * nur fuer Tabellen, die eine Geometriespalte tragen: an einer Tabelle
     * ohne geht kein SRID verloren, und `R365` stand dort fruher an **jeder**
     * Tabelle, auch an rein numerischen.
     */
    fun read(
        session: JdbcOperations,
        schema: String,
        table: String,
        notes: MutableList<SchemaReadNote>,
    ): Scan = try {
        Scan.Readable(OracleMetadataQueries.listGeometryMetadata(session, schema, table).associateBy { it.column })
    } catch (e: Exception) {
        notes += SchemaReadNote(
            severity = SchemaReadSeverity.WARNING,
            code = "R365",
            objectName = table,
            message = "ALL_SDO_GEOM_METADATA is not readable (${e.message?.lineSequence()?.firstOrNull()}); " +
                "the geometry columns of '$table' are read without a coordinate system.",
            hint = "Install Oracle Spatial, or grant SELECT on the metadata view; " +
                "alternatively declare the SRID in the schema file.",
        )
        Scan.Unreadable
    }

    /** Der gelesene SRID einer Spalte, oder `null`. */
    fun sridOf(scan: Scan, column: String): Int? = (scan as? Scan.Readable)?.rows?.get(column)?.srid

    /**
     * `R370` je Geometriespalte, deren Zeile fehlt — mit dem Text, der zum
     * Fall passt.
     *
     * Der Reader unterscheidet beide Faelle **deterministisch am Namen**: ist
     * Tabellen- oder Spaltenname nicht gleich seiner Grossschreibung, kann
     * Oracle die Zeile gar nicht fuehren; sind beide grossgeschrieben, ist
     * einfach keine registriert. Kein Text raet.
     *
     * Im `R365`-Fall entsteht nichts: die Sicht ist dort nicht lesbar.
     */
    fun noteMissingSrids(
        table: String,
        geometryColumns: List<String>,
        scan: Scan,
        notes: MutableList<SchemaReadNote>,
    ) {
        val readable = scan as? Scan.Readable ?: return
        for (column in geometryColumns) {
            if (readable.rows[column]?.srid != null) continue
            notes += if (table == table.uppercase() && column == column.uppercase()) {
                unregisteredRowNote(table, column)
            } else {
                unrepresentableRowNote(table, column)
            }
        }
    }

    /**
     * Die Zeile **kann es nicht geben**: der Name ist quotiert klein- oder
     * gemischtgeschrieben, und Oracle schreibt ihn in der Metadatenzeile
     * gross. Eine Zeile mit dem grossgeschriebenen Namen benennt eine andere
     * Tabelle — sie von Hand einzufuegen ist deshalb **kein** Ausweg.
     */
    private fun unrepresentableRowNote(table: String, column: String) = SchemaReadNote(
        severity = SchemaReadSeverity.WARNING,
        code = "R370",
        objectName = "$table.$column",
        message = "No coordinate system read for geometry column '$table.$column': Oracle upper-cases the " +
            "table and column name in USER_SDO_GEOM_METADATA, so no row can describe a quoted " +
            "lower- or mixed-case name like this one. The column is read as geometry without an SRID.",
        hint = "Declare the SRID on the column in the schema file, or create the table unquoted " +
            "(upper-case) and register its metadata row. Do not insert the row by hand for this table — " +
            "Oracle would store an upper-case name, which names a different table.",
    )

    /**
     * Die Zeile **fehlt**: beide Namen sind grossgeschrieben, es ist nur
     * keine registriert. Hier ist die Zeile der richtige, moegliche Ausweg.
     */
    private fun unregisteredRowNote(table: String, column: String) = SchemaReadNote(
        severity = SchemaReadSeverity.WARNING,
        code = "R370",
        objectName = "$table.$column",
        message = "No coordinate system read for geometry column '$table.$column': " +
            "USER_SDO_GEOM_METADATA has no row for it, so no spatial reference system is declared. " +
            "The column is read as geometry without an SRID.",
        hint = "Register the row in USER_SDO_GEOM_METADATA, or declare the SRID on the column in the " +
            "schema file.",
    )
}
