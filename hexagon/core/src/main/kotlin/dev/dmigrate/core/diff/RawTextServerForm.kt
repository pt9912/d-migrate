package dev.dmigrate.core.diff

/**
 * Was der Server aus einem Autorentext machen wuerde — beantwortet, ohne das
 * Ziel anzufassen.
 *
 * Die zweite Quelle fuer dieselbe Entscheidung, die [RawTextAuthorship] aus der
 * Herkunft beantwortet. Sie greift dort, wo es **keine** Herkunft gibt: beim
 * ersten Lauf gegen eine bestehende Datenbank, oder wenn das Dokument verloren
 * ging.
 *
 * Der Weg dorthin ist ein Wegwerf-Schema auf demselben Server: das Soll wird
 * dort angewandt, die Katalogform gelesen, das Schema verworfen. Beide Seiten
 * des Vergleichs stehen dann in Serverform — und zwei Serverformen lassen sich
 * vergleichen, Autorentext gegen Katalogform nicht.
 *
 * Gemessen gegen PostgreSQL 16: die Form im Sandkasten ist zeichengleich die
 * des Ziels. Ob ein Dialekt das ueberhaupt kann, sagt
 * `DialectCapabilities.supportsRawTextSandbox` — bei Oracle ist ein Schema ein
 * Benutzer, und den darf ein Migrationsnutzer nicht anlegen.
 *
 * `null` heisst: fuer dieses Feld liegt keine Serverform vor. Dann entscheidet
 * wie bisher der Textvergleich.
 */
fun interface RawTextServerForm {

    fun deparsed(
        objectType: String,
        objectPath: List<String>,
        field: String,
        keyPosition: Int?,
    ): String?
}
