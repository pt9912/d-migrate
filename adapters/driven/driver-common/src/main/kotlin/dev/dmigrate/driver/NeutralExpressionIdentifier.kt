package dev.dmigrate.driver

/**
 * Wie ein Reader einen Bezeichner, den der Server in **seiner** Quotierung
 * zurueckgibt (`[Name]` bei SQL Server, `` `Name` `` bei MySQL), in einen
 * rohen neutralen Ausdruck schreibt: ein rein kleingeschriebener Name bleibt
 * nackt, jeder andere wird zum ANSI-Doppelquote `"Name"`.
 *
 * Die Kleinschreibung ist die Grenze, nicht die blosse Wohlgeformtheit: die
 * Generatoren quoten Spaltennamen immer ([SqlIdentifiers]), eine
 * PascalCase-Spalte steht im PostgreSQL-Ziel also als `"CustomerID"`, und ein
 * unquotiertes `CustomerID` faltet dort auf `customerid` — `column
 * "customerid" does not exist`. Dieselbe Konvention liefert PostgreSQLs eigener
 * Reverse (`pg_get_constraintdef` quotet genau diese Faelle).
 *
 * `"…"` ist im neutralen Modell immer ein Bezeichner. Das einzige Ziel, das es
 * anders liest (MySQL ohne `ANSI_QUOTES`: als Zeichenkette), setzt es beim
 * Rendern in seine eigene Quotierung um (`spec/ddl-generation-rules.md`,
 * „Roher Ausdruckstext").
 *
 * **Ein reserviertes Wort quotiert das Ziel, nicht das Modell.** Ein
 * kleingeschriebenes `key` oder `order` bleibt hier nackt: welche Woerter
 * reserviert sind, weiss nur der Zieldialekt, und das neutrale Modell kennt
 * ihn nicht. Der MySQL-Generator setzt die Backticks beim Rendern wieder
 * (`MysqlReservedWords`, `spec/ddl-generation-rules.md`, „Roher
 * Ausdruckstext"). Fuer PostgreSQL, SQL Server und Oracle tut das heute
 * niemand — dort scheitert ein solcher Ausdruck am Server, laut und mit
 * dessen Meldung; der Posten steht in
 * `docs/planning/open/nackte-reservierte-woerter-im-rohen-ausdruck.md`.
 */
object NeutralExpressionIdentifier {

    private val LOWERCASE_IDENTIFIER = Regex("""[a-z_][a-z0-9_]*""")

    /** [name] ist der entpackte Name, ohne die Quotierung des Servers. */
    fun of(name: String): String =
        if (LOWERCASE_IDENTIFIER.matches(name)) name else "\"" + name.replace("\"", "\"\"") + "\""
}
