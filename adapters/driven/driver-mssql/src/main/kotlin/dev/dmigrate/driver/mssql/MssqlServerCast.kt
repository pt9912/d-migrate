package dev.dmigrate.driver.mssql

/**
 * Die Serverform der `CAST`-Huelle an einer berechneten Spalte: der Text, den
 * SQL Server in `sys.computed_columns.definition` ablegt, und seine
 * Rueckfuehrung. Aus `CAST(<ausdruck> AS <typ>)` (Generator,
 * [MssqlTypeMapper.castTargetSql]) wird `CONVERT([typ],<ausdruck>)`; auf der
 * **normalisierten** Form (P12) fuehrt [MssqlServerCast.withoutServerTypeCast]
 * die aeusserste Huelle zurueck, gegen den Katalogtext des Spaltentyps.
 */
internal object MssqlServerCast {

    /** `sys.columns.max_length` fuer `MAX`. */
    private const val LENGTH_MAX = -1

    /** Der Server legt `CAST` wörtlich als `CONVERT` ab — auch kleingeschrieben erkannt. */
    private const val SERVER_CAST = "convert"

    /**
     * Der Spaltentyp als Katalogtext — dieselbe Schreibweise, in der der Server
     * einen Typnamen in `sys.computed_columns.definition` ablegt
     * (kleingeschrieben, Parameter in Klammern, `max` unbeschnitten). Genau
     * gegen diesen Text vergleicht [withoutServerTypeCast] den Typ-Token einer
     * `CONVERT`-Huelle; aus demselben Katalog gelesen, deckt er mit ihr.
     *
     * `sys.columns.max_length` zaehlt **Bytes** (`-1` = MAX) — Unicode-Typen
     * tragen 2 Bytes je Zeichen. `time`/`datetime2`/`datetimeoffset` fuehren
     * ihre Sekundenpraezision in `scale`; `decimal`/`numeric` in `precision`
     * und `scale`. Typen ohne Parameter (`int`, `datetime`, `float`, …) stehen
     * nackt — `float` traegt Praezision 53 im Katalog, im Textnamen nie.
     */
    fun catalogTypeText(typeName: String, maxLength: Int?, precision: Int?, scale: Int?): String {
        val name = typeName.lowercase()
        return when (name) {
            "decimal", "numeric" -> "$name(${precision ?: 18},${scale ?: 0})"
            "nvarchar", "sysname" -> "$name${lengthText(maxLength, 2)}"
            "nchar" -> "$name${lengthText(maxLength, 2)}"
            "varchar" -> "$name${lengthText(maxLength, 1)}"
            "char" -> "$name${lengthText(maxLength, 1)}"
            "varbinary" -> "$name${lengthText(maxLength, 1)}"
            "time", "datetime2", "datetimeoffset" -> "$name(${scale ?: 7})"
            else -> name
        }
    }

    private fun lengthText(maxLength: Int?, charsPerByte: Int): String {
        val length = when (maxLength) {
            null -> return ""
            LENGTH_MAX -> "max"
            else -> (maxLength / charsPerByte).toString()
        }
        return "($length)"
    }

    /**
     * Entfernt die aeusserste `CONVERT(<typ>, <ausdruck>)`-Huelle auf dem
     * normalisierten Text — genau dann, wenn `<typ>` dem Spaltentyp entspricht
     * ([catalogTypeText]), genau zwei Argumente stehen (kein Stilargument) und
     * nach der Huelle nichts folgt.
     *
     * Das ist die Rueckfuehrung der Huelle, die der Generator an eine
     * berechnete Spalte haengt: `CAST(<ausdruck> AS <typ>)` legt der Server als
     * `CONVERT` ab, mit dem Typnamen geklammert — auf dem rohen Katalogtext
     * traefe ihn der Vergleich nicht (P12: Normalform). Eine Autorenhuelle auf
     * denselben Typ ist von der Generatorhuelle ununterscheidbar; ihre
     * Entfernung ist typidentisch bedeutungserhaltend. Alles andere bleibt: eine
     * Huelle auf einen **anderen** Typ, ein Stilargument — der Server legt es
     * eingeklammert als **drittes** Argument ab (`CONVERT(varchar(30),x,(126))`)
     * — und jede **innere** Huelle.
     *
     * **Nicht** hierdurch laeuft [MssqlHashPartitionRecognition]: die erkennt
     * die Hash-Emulation an der **Serverform** des Katalogtexts.
     */
    fun withoutServerTypeCast(normalized: String, declaredTypeText: String): String {
        val arguments = serverCastArguments(normalized) ?: return normalized
        if (arguments.size != 2) return normalized
        if (arguments[0].compareKey() != declaredTypeText.compareKey()) return normalized
        return arguments[1].trim()
    }

    private fun String.compareKey(): String = filterNot { it.isWhitespace() }.lowercase()

    /** Die Argumente der aeussersten `CONVERT(…)`-Huelle, oder `null`, wenn das keine ist. */
    private fun serverCastArguments(normalized: String): List<String>? {
        if (!normalized.startsWith(SERVER_CAST, ignoreCase = true)) return null
        if (normalized.getOrNull(SERVER_CAST.length) != '(') return null
        val closeParen = matchingParenEnd(normalized, SERVER_CAST.length) ?: return null
        // Nach der Huelle nichts: `CONVERT(t,x)+1` ist kein Server-Cast.
        if (closeParen != normalized.length - 1) return null
        return splitTopLevelCommas(normalized.substring(SERVER_CAST.length + 1, closeParen))
    }

    private fun matchingParenEnd(text: String, openParen: Int): Int? {
        var depth = 0
        var index = openParen
        while (index < text.length) {
            when {
                text[index] == '\'' -> index = MssqlTypeMapping.endOfStringLiteral(text, index)
                text[index] == '(' -> {
                    depth++
                    index++
                }
                text[index] == ')' -> {
                    depth--
                    if (depth == 0) return index
                    index++
                }
                else -> index++
            }
        }
        return null
    }

    // Kommas auf Klammer-Tiefe 0 — `decimal(14,2)` traegt sein Komma in Tiefe 1.
    private fun splitTopLevelCommas(text: String): List<String> {
        val segments = mutableListOf<String>()
        var depth = 0
        var start = 0
        var index = 0
        while (index < text.length) {
            when {
                text[index] == '\'' -> index = MssqlTypeMapping.endOfStringLiteral(text, index)
                text[index] == '(' -> {
                    depth++
                    index++
                }
                text[index] == ')' -> {
                    depth--
                    index++
                }
                text[index] == ',' && depth == 0 -> {
                    segments += text.substring(start, index)
                    start = index + 1
                    index++
                }
                else -> index++
            }
        }
        segments += text.substring(start)
        return segments
    }
}
