package dev.dmigrate.driver.mssql

/**
 * Erkennt die von [MssqlHashPartitionEmulation] erzeugte HASH-Emulation im
 * Katalog wieder.
 *
 * SQL Server kennt nur RANGE; eine HASH-Partitionierung entsteht dort als
 * persistierte berechnete Spalte plus RANGE-Funktion an den Eimergrenzen.
 * Ohne die Wiedererkennung liest der Reverse genau das zurueck, was dasteht —
 * eine RANGE-Partitionierung ueber einer Spalte, die im Soll-Schema gar nicht
 * vorkommt. Soll und Ist wichen dann in Partitionstyp, Schluessel **und**
 * Spaltenbestand ab, und `schema migrate` plante bei jedem Lauf erneut.
 *
 * **Der Server schreibt den Ausdruck um.** Was
 * [MssqlHashPartitionEmulation.bucketExpression] als
 * `ABS(CHECKSUM([a], [b]) % 4)` erzeugt, legt `sys.computed_columns` als
 * `(abs(checksum([a],[b])%(4)))` ab: Funktionsnamen klein, Bezeichner in
 * eckigen Klammern, das Literal geklammert, keine Leerzeichen (gemessen
 * gegen SQL Server 2022).
 *
 * Der Bezeichner selbst bleibt dabei unveraendert — nur die Funktionsnamen
 * werden gefaltet. Der Vergleich laeuft deshalb ohne Kleinschreiben des
 * Ganzen und ohne Leerraum-Entfernen: ein Spaltenname darf Leerzeichen,
 * Kommata und (als `]]`) eckige Klammern enthalten.
 *
 * **Erkennen oder zurueckfallen, nie raten.** Passt der Ausdruck nicht auf
 * diese Form, liefert [recognize] `null`, und der Leser bleibt beim heutigen
 * RANGE. Eine von Hand angelegte Spalte, die zufaellig so heisst, wird damit
 * nicht zur Emulation erklaert.
 */
internal object MssqlHashPartitionRecognition {

    /** Der Schluessel und die Eimerzahl hinter einer erkannten Emulation. */
    data class Recognized(val key: List<String>, val modulus: Int)

    /**
     * `(abs(checksum(<args>) % (<n>)))`, mit Leerraum an jeder Fuge geduldet.
     * Die Argumentgruppe faengt traege, damit das `%` dahinter das
     * schliessende `)` von `checksum` festlegt.
     */
    private val SHAPE = Regex(
        """^\s*\(\s*abs\s*\(\s*checksum\s*\((.+?)\)\s*%\s*\(?\s*(\d+)\s*\)?\s*\)\s*\)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Ein Bezeichner in eckigen Klammern; `]]` steht fuer ein `]` im Namen. */
    private val BRACKETED = Regex("""\[((?:[^\]]|\]\])*)\]""")

    /**
     * Der Schluessel und der Modulus hinter [computedDefinition], oder `null`,
     * wenn der Ausdruck nicht die erzeugte Form hat.
     *
     * [boundaries] ist die unabhaengige Gegenprobe: die RANGE-Funktion muss
     * genau die Schnittpunkte `1 … modulus-1` fuehren. Stimmen Ausdruck und
     * Grenzen nicht ueberein, ist es keine Emulation dieses Werkzeugs —
     * jemand hat an einem der beiden Teile gedreht, und dann ist der
     * RANGE-Rueckfall die ehrlichere Auskunft.
     */
    fun recognize(computedDefinition: String?, boundaries: List<String>): Recognized? {
        val match = SHAPE.matchEntire(computedDefinition ?: return null) ?: return null
        val modulus = match.groupValues[2].toIntOrNull()?.takeIf { it >= 2 } ?: return null
        if (boundaries != (1 until modulus).map { it.toString() }) return null
        val key = bracketedList(match.groupValues[1]) ?: return null
        return Recognized(key, modulus)
    }

    /**
     * Die kommagetrennten Bezeichner in [arguments], oder `null`, sobald
     * etwas anderes dazwischensteht.
     *
     * Bewusst kein `split(',')`: ein SQL-Server-Bezeichner darf ein Komma
     * enthalten (`[a,b]`), und ein Trennen am Zeichen zerschnitte ihn. Der
     * Scanner laeuft deshalb ueber die Klammerpaare und verlangt, dass
     * zwischen ihnen ausschliesslich Kommata und Leerraum stehen — was
     * `checksum(*)` oder einen unquotierten Namen zuverlaessig ablehnt.
     */
    private fun bracketedList(arguments: String): List<String>? {
        val names = mutableListOf<String>()
        var cursor = 0
        for (token in BRACKETED.findAll(arguments)) {
            if (arguments.substring(cursor, token.range.first).any { !it.isWhitespace() && it != ',' }) return null
            if (names.isNotEmpty() && !arguments.substring(cursor, token.range.first).contains(',')) return null
            names += token.groupValues[1].replace("]]", "]")
            cursor = token.range.last + 1
        }
        if (arguments.substring(cursor).any { !it.isWhitespace() }) return null
        return names.ifEmpty { null }
    }
}
