package dev.dmigrate.format.data

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.format.data.converters.TypeConverterRegistry
import java.sql.Types
import java.time.format.DateTimeParseException

/**
 * Per-Spalten-Typ-Hint für den [ValueDeserializer]. Lebt im `formats`-
 * Modul (statt in `core`), weil der Deserializer der einzige Konsument ist
 * und der Hint sonst gegen die 0.3.0-Architektur-Regel "kein JDBC-
 * spezifisches Feld in core" verstoßen würde (siehe L15 im 0.4.0-Plan).
 *
 * Der Hint wird vom Phase-D `StreamingImporter` aus den Writer-Side-
 * Metadaten (`TableImportSession.targetColumns` plus eine vom Writer
 * gelieferte typed metadata struct) gebaut und als Lookup-Closure
 * `(columnName: String) -> JdbcTypeHint?` an den Deserializer übergeben.
 *
 * @property jdbcType JDBC-Typcode aus
 *   `ResultSetMetaData.getColumnType(i)`, z.B. [java.sql.Types.VARCHAR].
 *   Primärer Typ-Anker für den Deserializer.
 * @property sqlTypeName Optional: dialekt-spezifischer Type-Name aus
 *   `ResultSetMetaData.getColumnTypeName(i)`. Sekundärer Hint für
 *   Pfade, in denen der JDBC-Typcode mehrdeutig ist (PG `Types.OTHER`
 *   für UUID/JSON/JSONB/INTERVAL, MySQL `BIT(1)` vs `BIT(N)`).
 * @property precision Optionale Dezimalpräzision aus JDBC-Metadaten.
 *   Wird für `NUMERIC`/`DECIMAL` gebraucht, um Werte mit
 *   `precision > 18` auf dem exakten `BigDecimal`-Pfad zu halten.
 * @property scale Optionale Dezimalskala aus JDBC-Metadaten. Eine
 *   positive Scale erzwingt für `NUMERIC`/`DECIMAL` den exakten
 *   `BigDecimal`-Pfad, auch wenn das aktuelle Input-Token ganzzahlig
 *   aussieht.
 */
data class JdbcTypeHint(
    val jdbcType: Int,
    val sqlTypeName: String? = null,
    val precision: Int? = null,
    val scale: Int? = null,
)

/**
 * Inverse zur [ValueSerializer]-Mapping-Tabelle aus Plan-0.3.0 §6.4.1.
 * Konvertiert einen Format-spezifischen Input-Wert (JSON-String / -Number /
 * -Array / YAML-Mapping / CSV-String) in den passenden Java-Wert, den der
 * `TableImportSession.write(...)`-Pfad per `setObject(idx, value)` an das
 * PreparedStatement binden kann.
 *
 * Plan: implementation-plan-0.4.0.md §3.5.2 / L15.
 *
 * **Lookup-Closure-API (L15)**: Statt einen JDBC-Typ-Slot auf dem
 * `core.ColumnDescriptor` zu führen (was die 0.3.0-Architektur-Regel "kein
 * JDBC-spezifisches Feld in core" verletzen würde), bekommt der Deserializer
 * eine Lookup-Closure `(columnName: String) -> JdbcTypeHint?` durchgereicht.
 * Damit:
 *
 * - bleibt `core.ColumnDescriptor` unverändert (0.3.0-konform)
 * - lebt der JDBC-Hint lokalisiert in `formats`, wo der Deserializer ihn
 *   ohnehin braucht
 * - kann der Phase-D `StreamingImporter` den Lookup einmal pro Tabelle aus
 *   den Writer-Metadaten bauen (eine Closure pro Tabelle, nicht pro Row)
 * - sind Tests trivial: ein Map-Literal als Lookup-Quelle reicht
 *
 * **Primärer Typ-Anker ist der JDBC-Typcode** ([JdbcTypeHint.jdbcType] aus
 * `java.sql.Types`), NICHT der dialektspezifische `sqlTypeName`-String.
 * Letzterer dient nur als sekundärer Hint (z.B. um PG-`jsonb` von PG-`json`
 * zu trennen, oder MySQL-`BIT(1)` von `BIT(N)` zu unterscheiden). Damit
 * verhalten sich PG/MySQL/SQLite auf Deserializer-Ebene konsistent, auch
 * wenn ihre `getColumnTypeName`-Strings abweichen.
 *
 * **Format-Quellen**:
 * - JSON: Input-Werte sind bereits vom Parser typisiert (String, Long/
 *   Double, Boolean, null, List, Map). Der Deserializer akzeptiert diese
 *   Java-Typen direkt.
 * - YAML: identisch zu JSON — SnakeYAML Engine Events werden vom Reader
 *   bereits in Java-Primitives / `List` / `Map` überführt.
 * - CSV: alle Werte kommen als `String` rein; der Deserializer castet
 *   anhand des `jdbcType`-Hints. CSV-NULL ist der Wert, der exakt
 *   [csvNullString] entspricht.
 *
 * **Strenge Regeln** (bewusst keine „tolerante" Interpretation):
 * - BOOLEAN: nur `true`/`false` (case-insensitive), sowohl aus JSON-
 *   Boolean als auch aus String. `0`/`1`/`yes`/`no` sind keine
 *   gültigen Boolean-Quellwerte und werfen (L3 / §3.5.2).
 * - TIMESTAMP ohne Offset: ein Input mit Offset/Zone wird als Typfehler
 *   behandelt, nicht still abgeschnitten (§3.5.2).
 * - JSON Array/YAML Sequence für eine nicht-ARRAY-Spalte: Format-Fehler.
 * - Array-/Treiberobjekte werden NICHT hier erzeugt. `formats` bleibt
 *   JDBC-frei; der Writer-Layer materialisiert `java.sql.Array`-
 *   Instanzen selbst (§3.5.2 Ende).
 *
 * **Fehler-Pfad**: Alle typbezogenen Fehler werfen
 * [ImportSchemaMismatchException], die der `StreamingImporter` über
 * `--on-error` auswertet (§6.5). Die Meldung nennt Tabelle, Spalte und
 * den konkret erwarteten Typ, damit der User die Quell-Datei gezielt
 * korrigieren kann.
 *
 * **Unbekannte Spalten**: Wenn die [typeHintOf]-Closure für einen
 * Spaltennamen `null` liefert (Spalte ist im Reader vorhanden, aber im
 * Target nicht — sollte vor dem Aufruf bereits per Header-Mapping abgefangen
 * sein), läuft der Wert als Passthrough durch. Das ist Best-Effort:
 * der Writer wird beim `setObject` typischerweise scheitern, aber der
 * Deserializer darf hier nicht raten.
 */
class ValueDeserializer(
    /**
     * Lookup-Closure: liefert den JDBC-Hint für eine gegebene Spalte oder
     * `null`, wenn die Spalte im Target unbekannt ist. Wird einmal pro
     * Tabelle vom [StreamingImporter] aus den Writer-Metadaten gebaut.
     */
    private val typeHintOf: (columnName: String) -> JdbcTypeHint?,
    /** Nullstring für CSV-Werte; alles andere Format ignoriert das Feld. */
    private val csvNullString: String = "",
) {

    /**
     * Convertiert einen format-getypten Input-Wert zu einem Java-Wert,
     * passend zum JDBC-Hint, den [typeHintOf] für diese Spalte liefert.
     *
     * @param table Tabellenname (für Fehlermeldungen)
     * @param columnName Spaltenname; wird in den [typeHintOf]-Lookup
     *   gegeben.
     * @param value Roher Input-Wert vom Reader. `null` wird direkt
     *   durchgereicht (SQL NULL); CSV-Null-Sentinel
     *   ([csvNullString]) wird ebenfalls zu `null`.
     * @param isCsvSource `true`, wenn der Wert aus einem CSV-Reader
     *   kommt — dann wird ein String gegen den `csvNullString`
     *   verglichen. Für JSON/YAML-Quellen `false`.
     */
    fun deserialize(
        table: String,
        columnName: String,
        value: Any?,
        isCsvSource: Boolean = false,
    ): Any? {
        // Null-Handling ist vor dem Type-Dispatch: jeder beliebige
        // Spalten-Typ akzeptiert SQL NULL.
        if (value == null) return null
        if (isCsvSource && value is String && value == csvNullString) return null

        val hint = typeHintOf(columnName)
            ?: return value // Passthrough für unbekannte Spalten

        return try {
            dispatch(hint, table, columnName, value, isCsvSource)
        } catch (e: ImportSchemaMismatchException) {
            throw e
        } catch (e: NumberFormatException) {
            throw DeserializerHelpers.typeMismatch(table, columnName, hint, value, e.message, e)
        } catch (e: DateTimeParseException) {
            throw DeserializerHelpers.typeMismatch(table, columnName, hint, value, e.message, e)
        } catch (e: IllegalArgumentException) {
            throw DeserializerHelpers.typeMismatch(table, columnName, hint, value, e.message, e)
        }
    }

    private fun dispatch(
        hint: JdbcTypeHint,
        table: String,
        columnName: String,
        value: Any,
        isCsvSource: Boolean,
    ): Any? {
        if (hint.jdbcType == Types.NULL) return null
        val converter = TypeConverterRegistry.converterFor(hint.jdbcType) ?: return value
        return converter.convert(ConversionContext(hint, table, columnName, isCsvSource), value)
    }
}
