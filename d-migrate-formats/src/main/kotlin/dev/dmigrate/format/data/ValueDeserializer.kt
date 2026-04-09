package dev.dmigrate.format.data

import dev.dmigrate.core.data.ImportSchemaMismatchException
import java.math.BigDecimal
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.BitSet
import java.util.Base64
import java.util.UUID

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
 */
data class JdbcTypeHint(
    val jdbcType: Int,
    val sqlTypeName: String? = null,
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
            throw typeMismatch(table, columnName, hint, value, e.message, e)
        } catch (e: DateTimeParseException) {
            throw typeMismatch(table, columnName, hint, value, e.message, e)
        } catch (e: IllegalArgumentException) {
            throw typeMismatch(table, columnName, hint, value, e.message, e)
        }
    }

    private fun dispatch(
        hint: JdbcTypeHint,
        table: String,
        columnName: String,
        value: Any,
        isCsvSource: Boolean,
    ): Any? = when (hint.jdbcType) {
        // ─── String / Char / CLOB ─────────────────────────────
        Types.CHAR,
        Types.VARCHAR,
        Types.LONGVARCHAR,
        Types.NCHAR,
        Types.NVARCHAR,
        Types.LONGNVARCHAR,
        Types.CLOB,
        Types.NCLOB,
        Types.SQLXML,
            -> toStringValue(value)

        // ─── Boolean ──────────────────────────────────────────
        Types.BOOLEAN, Types.BIT -> toBoolean(hint, columnName, value)

        // ─── Integer-Familie ──────────────────────────────────
        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
            toLong(columnName, value)

        // ─── Floating-point ───────────────────────────────────
        Types.REAL, Types.FLOAT, Types.DOUBLE -> toDouble(columnName, value)

        // ─── Exact numeric ────────────────────────────────────
        Types.NUMERIC, Types.DECIMAL -> toBigDecimal(columnName, value)

        // ─── Datum / Zeit ─────────────────────────────────────
        Types.DATE -> toLocalDate(columnName, value)
        Types.TIME, Types.TIME_WITH_TIMEZONE -> toLocalTime(columnName, value)
        Types.TIMESTAMP -> toLocalDateTime(columnName, value)
        Types.TIMESTAMP_WITH_TIMEZONE -> toOffsetDateTime(columnName, value)

        // ─── UUID / OTHER ─────────────────────────────────────
        // java.sql.Types.OTHER ist der PG-Sammel-Code für UUID, JSON,
        // JSONB, INTERVAL, XML etc. Der sqlTypeName-String differenziert.
        Types.OTHER -> toOther(hint, columnName, value, isCsvSource)

        // ─── Binär / BLOB ─────────────────────────────────────
        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
            toByteArray(columnName, value)

        // ─── Struktur / Array ─────────────────────────────────
        // 0.4.0: Arrays kommen als Kotlin List<Any?> in den Writer;
        // der Writer materialisiert daraus per
        // Connection.createArrayOf(...) ein java.sql.Array.
        Types.ARRAY -> toList(columnName, value)

        // ─── JDBC NULL ────────────────────────────────────────
        Types.NULL -> null

        // ─── Rest: passthrough ────────────────────────────────
        else -> value
    }

    // ──────────────────────────────────────────────────────────
    // Typ-Konverter
    // ──────────────────────────────────────────────────────────

    private fun toStringValue(value: Any): String = when (value) {
        is String -> value
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> value.toString()
    }

    private fun toBoolean(hint: JdbcTypeHint, columnName: String, value: Any): Any {
        // BIT(1) wird wie BOOLEAN behandelt (§3.5.2 mapping table).
        // BIT(N>1) → BitSet-Konvertierung.
        val sqlTypeName = hint.sqlTypeName?.uppercase()
        if (sqlTypeName != null && isMultiBit(sqlTypeName)) {
            return toBitSet(columnName, value)
        }
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw ImportSchemaMismatchException(
                    "column '$columnName' expects BOOLEAN (true/false, case-insensitive), got '$value'"
                )
            }
            // Number als Boolean ist in 0.4.0 bewusst verboten (L3).
            is Number -> throw ImportSchemaMismatchException(
                "column '$columnName' expects BOOLEAN, got number $value — " +
                    "0/1/yes/no are not accepted, use explicit true/false"
            )
            else -> throw ImportSchemaMismatchException(
                "column '$columnName' expects BOOLEAN, got ${value::class.simpleName}"
            )
        }
    }

    private fun toLong(columnName: String, value: Any): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Number -> {
            // Double/Float sind unzulässig für Integer-Spalten. Ein
            // JSON-Number-Token mit `.` oder `e`/`E` hätte der Reader
            // bereits als Double geliefert; hier fangen wir das als
            // klarer Fehler — auch wenn der Double-Wert zufällig
            // ganzzahlig ist (`1.0`).
            throw ImportSchemaMismatchException(
                "column '$columnName' expects integer, got non-integer number $value " +
                    "(source token had decimal point or exponent)"
            )
        }
        is String -> {
            val trimmed = value.trim()
            // First try the strict integer parse. If that works we are done.
            val direct = trimmed.toLongOrNull()
            if (direct != null) {
                direct
            } else {
                // Parse failed — distinguish "looks numeric but is decimal"
                // from "arbitrary non-numeric string" so the error message
                // stays specific. We only claim "decimal string" if the
                // input actually **parses as a number** (Double).
                val asDouble = trimmed.toDoubleOrNull()
                if (asDouble != null) {
                    throw ImportSchemaMismatchException(
                        "column '$columnName' expects integer, got decimal string '$value'"
                    )
                }
                // Not numeric at all — fall through to the outer
                // NumberFormatException catch so the generic typeMismatch
                // helper builds the full table/column/jdbcType message.
                trimmed.toLong() // throws NumberFormatException
            }
        }
        is Boolean -> throw ImportSchemaMismatchException(
            "column '$columnName' expects integer, got boolean $value"
        )
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects integer, got ${value::class.simpleName}"
        )
    }

    private fun toDouble(columnName: String, value: Any): Double = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        is String -> {
            // Locale-invariant parse (nie Locale.GERMAN mit `,`).
            value.trim().toDouble()
        }
        is Boolean -> throw ImportSchemaMismatchException(
            "column '$columnName' expects floating-point, got boolean $value"
        )
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects floating-point, got ${value::class.simpleName}"
        )
    }

    private fun toBigDecimal(columnName: String, value: Any): BigDecimal = when (value) {
        is BigDecimal -> value
        is Long, is Int, is Short, is Byte -> BigDecimal((value as Number).toLong())
        // H-A1 fix: Float/Double via String-Konstruktor, nicht über
        // BigDecimal.valueOf(double) mit binärer Float-Repräsentation.
        // Sonst würde 0.1f → 0.10000000149011612 ankommen.
        is Double -> BigDecimal(value.toString())
        is Float -> BigDecimal(value.toString())
        is Number -> BigDecimal(value.toString())
        is String -> BigDecimal(value.trim())
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects NUMERIC/DECIMAL, got ${value::class.simpleName}"
        )
    }

    private fun toLocalDate(columnName: String, value: Any): LocalDate = when (value) {
        is LocalDate -> value
        is String -> LocalDate.parse(value.trim())
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects DATE, got ${value::class.simpleName}"
        )
    }

    private fun toLocalTime(columnName: String, value: Any): LocalTime = when (value) {
        is LocalTime -> value
        is String -> LocalTime.parse(value.trim())
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects TIME, got ${value::class.simpleName}"
        )
    }

    private fun toLocalDateTime(columnName: String, value: Any): LocalDateTime = when (value) {
        is LocalDateTime -> value
        is String -> {
            val trimmed = value.trim()
            // Explizit verbieten, dass ein String mit Offset/Zone still
            // zu LocalDateTime abgeschnitten wird. `2026-04-07T10:00:00+02:00`
            // muss in eine TIMESTAMP_WITH_TIMEZONE-Spalte, nicht hier.
            if (hasOffsetOrZone(trimmed)) {
                throw ImportSchemaMismatchException(
                    "column '$columnName' expects TIMESTAMP without time zone, " +
                        "got '$value' with explicit offset/zone"
                )
            }
            LocalDateTime.parse(trimmed)
        }
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects TIMESTAMP, got ${value::class.simpleName}"
        )
    }

    private fun toOffsetDateTime(columnName: String, value: Any): OffsetDateTime = when (value) {
        is OffsetDateTime -> value
        is String -> OffsetDateTime.parse(value.trim())
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects TIMESTAMP WITH TIME ZONE, got ${value::class.simpleName}"
        )
    }

    private fun toOther(hint: JdbcTypeHint, columnName: String, value: Any, isCsvSource: Boolean): Any {
        // OTHER ist der Sammel-Pfad für PG: UUID, JSON/JSONB, INTERVAL,
        // XML. Die Differenzierung läuft über den sqlTypeName-Hint.
        val sqlTypeName = hint.sqlTypeName?.lowercase() ?: ""
        return when {
            sqlTypeName == "uuid" -> when (value) {
                is UUID -> value
                is String -> UUID.fromString(value.trim())
                else -> throw ImportSchemaMismatchException(
                    "column '$columnName' expects UUID, got ${value::class.simpleName}"
                )
            }

            sqlTypeName == "json" || sqlTypeName == "jsonb" -> when (value) {
                // Map/List aus JSON/YAML → re-serialisieren als JSON-String,
                // den der PG-Writer später als PGobject bindet. Für CSV
                // kommt der Wert bereits als String rein.
                is String -> value
                is Map<*, *>, is List<*> -> simpleJsonString(value)
                else -> value.toString()
            }

            sqlTypeName == "interval" -> when (value) {
                is String -> value // PG-Writer bindet als PGobject("interval", value)
                else -> value.toString()
            }

            sqlTypeName == "xml" -> when (value) {
                is String -> value
                else -> value.toString()
            }

            // Unbekannt → Passthrough, der Writer versucht setObject.
            else -> value
        }
    }

    private fun toByteArray(columnName: String, value: Any): ByteArray = when (value) {
        is ByteArray -> value
        is String -> try {
            // Binärdaten aus JSON/YAML/CSV kommen als Base64.
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            throw ImportSchemaMismatchException(
                "column '$columnName' expects BINARY/BLOB (Base64), got invalid Base64 string"
            )
        }
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects BINARY/BLOB, got ${value::class.simpleName}"
        )
    }

    private fun toList(columnName: String, value: Any): List<Any?> = when (value) {
        is List<*> -> value.toList()
        is Array<*> -> value.toList()
        is String -> throw ImportSchemaMismatchException(
            "column '$columnName' expects ARRAY, got plain string " +
                "(use a JSON/YAML array literal, not a comma-separated list)"
        )
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects ARRAY, got ${value::class.simpleName}"
        )
    }

    private fun toBitSet(columnName: String, value: Any): BitSet = when (value) {
        is String -> {
            // Bit-String: '1010', LSB ist rechts (java.util.BitSet-Konvention:
            // bit 0 == rechts → wir rekonstruieren von rechts nach links).
            val trimmed = value.trim()
            if (trimmed.any { it != '0' && it != '1' }) {
                throw ImportSchemaMismatchException(
                    "column '$columnName' expects BIT(N) string of 0/1, got '$value'"
                )
            }
            val bits = BitSet(trimmed.length)
            val len = trimmed.length
            for (i in 0 until len) {
                if (trimmed[len - 1 - i] == '1') bits.set(i)
            }
            bits
        }
        is BitSet -> value
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects BIT(N) string, got ${value::class.simpleName}"
        )
    }

    // ──────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────

    private fun isMultiBit(sqlTypeName: String): Boolean {
        // MySQL: "BIT" + optional "(N)". BIT(1) wird wie BOOLEAN behandelt,
        // BIT(N>1) als BitSet.
        if (!sqlTypeName.startsWith("BIT")) return false
        val start = sqlTypeName.indexOf('(')
        val end = sqlTypeName.indexOf(')')
        if (start < 0 || end < 0 || end < start) return false
        val inner = sqlTypeName.substring(start + 1, end).trim()
        return inner.toIntOrNull()?.let { it > 1 } ?: false
    }

    /**
     * Heuristik für "TIMESTAMP-Wert trägt Offset/Zone, passt also nicht in
     * eine `TIMESTAMP`-Spalte ohne Zeitzone". Wir suchen nach `Z`, `+hh:mm`
     * oder `-hh:mm` nach einem `T`-Zeichen. Nicht perfekt, aber deckt die
     * Standard-Formen von ISO 8601 ab und vermeidet den still
     * abschneidenden Pfad.
     */
    private fun hasOffsetOrZone(s: String): Boolean {
        val tIdx = s.indexOf('T')
        if (tIdx < 0) return false
        val tail = s.substring(tIdx + 1)
        if (tail.endsWith('Z')) return true
        // Offset-Form: ±hh:mm oder ±hhmm am Ende (nach einem Doppelpunkt).
        // Wir prüfen einfach auf das Vorhandensein von `+` oder `-` im Tail.
        return tail.any { it == '+' } || (tail.indexOf('-') >= 0 && tail.length > 6)
    }

    /**
     * Kleines lokales JSON-Rendering für `Map`/`List`-Werte, damit der
     * PG-Writer einen `jsonb`-String erhält, ohne dass wir in `formats`
     * eine zweite Jackson-Dependency ziehen. Für rekursive Strukturen
     * korrekt, aber ohne Pretty-Printing.
     */
    private fun simpleJsonString(value: Any?): String = buildString {
        append(jsonToken(value))
    }

    private fun jsonToken(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        is String -> "\"" + escapeJson(value) + "\""
        is Map<*, *> -> value.entries.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { "\"${escapeJson(it.key.toString())}\":${jsonToken(it.value)}" }
        is List<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { jsonToken(it) }
        is Array<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { jsonToken(it) }
        else -> "\"${escapeJson(value.toString())}\""
    }

    private fun escapeJson(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }

    private fun typeMismatch(
        table: String,
        columnName: String,
        hint: JdbcTypeHint,
        value: Any?,
        underlyingMessage: String?,
        cause: Throwable?,
    ): ImportSchemaMismatchException {
        val hintText = underlyingMessage?.let { " ($it)" } ?: ""
        return ImportSchemaMismatchException(
            "table '$table' column '$columnName' (jdbcType=${hint.jdbcType}) " +
                "rejected value '$value'$hintText",
            cause = cause,
        )
    }
}
