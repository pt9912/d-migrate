package dev.dmigrate.driver

/**
 * Wie ein **unbekannter** Funktions-Default in die DDL kommt.
 *
 * Die Spec fuehrt `default: <funktion>` als uebersetzten Namen
 * (`current_timestamp`, `gen_uuid`); jeder Dialekt uebersetzt die, die er
 * kennt. Was uebrig bleibt, stammt aus einem Reverse und ist der Text des
 * Servers — und der ist nicht zwingend ein Name.
 *
 * **Klammern bekommt nur, was ein Name ist.** Die fuenf Dialekte trugen dafuer
 * dieselbe Zeile fuenfmal (`if (endsWith(")")) name else "$name()"`), und die
 * traf daneben, sobald der Text weder Name noch Aufruf war: aus dem
 * PostgreSQL-Default `ARRAY['NEW'::order_status]` wurde
 * `ARRAY['NEW'::order_status]()` — aus fremdem, aber gueltigem SQL unsinniges.
 * Gemeldet von einem Konsumenten.
 *
 * Ob der Text auf dem Ziel ueberhaupt gilt, entscheidet nicht diese Stelle,
 * sondern [ForeignFunctionDefaultFilter] — vorher und einmal fuer alle.
 */
object FunctionDefaultText {

    /** Ein blosser Bezeichner: `now`, `gen_random_uuid`, `sys.getdate`. */
    private val BARE_NAME = Regex("""[A-Za-z_][A-Za-z0-9_$.]*""")

    fun call(name: String): String {
        val text = name.trim()
        return if (BARE_NAME.matches(text)) "$text()" else text
    }
}
