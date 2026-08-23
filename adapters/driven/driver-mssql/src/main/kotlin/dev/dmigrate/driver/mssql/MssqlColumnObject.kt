package dev.dmigrate.driver.mssql

/**
 * Ein benanntes Objekt, das an einer Spalte haengt: DEFAULT, UNIQUE oder
 * CHECK. In T-SQL sind das eigenstaendige Eintraege in `sys.objects`, keine
 * Spalteneigenschaften — und ihre Namen sind **schema-global** eindeutig.
 *
 * Daraus folgt die Existenz dieses Typs. Der Generate-Pfad schreibt sie als
 * Klausel in die Spaltendeklaration; der Tabellen-Neubau des Diff-Pfads kann
 * das nicht, weil die alte Tabelle die Namen bis zu ihrem `DROP` belegt
 * (Msg 2714). Er braucht dieselben Objekte als nachgelagerte Statements.
 *
 * [MssqlColumnConstraintHelper.renderColumn] liefert deshalb Deklaration und
 * Objekte getrennt, und die beiden Formen entstehen aus derselben Liste — die
 * Frage „welche Objekte hat diese Spalte" wird nur einmal beantwortet.
 *
 * [body] traegt den variablen Teil: den Default-Ausdruck bei `DEFAULT`, den
 * Pruefausdruck bei `CHECK`, nichts bei `UNIQUE` (die Spalte steht in der
 * jeweiligen Rendering-Form).
 */
internal data class MssqlColumnObject(val kind: Kind, val name: String, val body: String) {

    internal enum class Kind { DEFAULT, UNIQUE, CHECK }
}
