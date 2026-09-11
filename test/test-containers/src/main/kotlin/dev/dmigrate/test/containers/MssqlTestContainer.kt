package dev.dmigrate.test.containers

import dev.dmigrate.test.images.TestImages
import org.testcontainers.containers.output.OutputFrame
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.time.Duration
import java.util.function.Consumer

/**
 * Wie lange ein SQL Server zum Hochkommen haben darf.
 *
 * Fuenf Minuten, seit die Suiten gegen 2025 laufen: der Container startete in
 * CI, nahm aber innerhalb der Testcontainers-Vorgabe noch keine Verbindungen
 * an (`Connection refused`), waehrend weitere Container desselben Laufs
 * durchkamen. Es ist also keine Eigenschaft des Bildes, sondern der Last.
 *
 * Die Frist allein hat es nicht geloest — die Ausfaelle kamen weiter, je Lauf
 * an einer anderen Spec. Deshalb schreibt [MssqlTestContainer] die Ausgabe des
 * Servers mit: ohne sie laesst sich nicht unterscheiden, ob er starb oder nur
 * langsam war, und ohne diese Unterscheidung ist jede weitere Frist geraten.
 */
val MSSQL_STARTUP_TIMEOUT: Duration = Duration.ofMinutes(5)

/**
 * So viele Zeilen je vom Anfang und vom Ende der Serverausgabe traegt eine
 * Fehlermeldung.
 *
 * Beide Enden, nicht nur das letzte: stirbt der Server frueh, steht der Grund
 * in den ersten Zeilen — und danach flutet sein Absturzsammler die Ausgabe mit
 * `find: … Permission denied`, bis vom Grund nichts mehr zu sehen waere.
 */
private const val LOG_EDGE_LINES = 40

/**
 * Der SQL-Server-Container der Tests — eine Stelle statt fuenfzehn.
 *
 * Die immer gleiche Vierergruppe (Lizenz annehmen, Verschluesselung im
 * JDBC-URL abschalten, Startfrist, Bild) stand bisher an jeder Aufrufstelle.
 * Wichtiger als die Wiederholung ist aber, was hier dazukommt und sich einzeln
 * nicht durchhalten liess:
 *
 * **Kommt der Server nicht hoch, sagt der Fehlschlag, was er selbst gemeldet
 * hat.** Testcontainers meldet sonst nur „Connection refused" nach Ablauf der
 * Frist — ob der Prozess starb oder bloss langsam war, steht dann nirgends.
 * Genau diese Frage blieb bei den CI-Ausfaellen offen. Die Ausgabe wird
 * deshalb im Hintergrund mitgeschrieben und nur im Fehlerfall angehaengt; ein
 * gelungener Start bleibt still.
 */
class MssqlTestContainer : MSSQLServerContainer(TestImages.MSSQL) {

    private val output = TailBuffer()

    init {
        // Das Image startet ueberhaupt nur mit akzeptierter Microsoft-EULA
        // (`ACCEPT_EULA=Y`, siehe docs/user/quality.md).
        acceptLicense()
        // mssql-jdbc ab 10 setzt `encrypt=true` als Vorgabe, der Container
        // traegt aber nur ein selbst signiertes Zertifikat.
        withUrlParam("encrypt", "false")
        withStartupTimeout(MSSQL_STARTUP_TIMEOUT)
        withLogConsumer(output)
        // Ein zweiter Versuch, und zwar als Milderung, nicht als Behebung.
        //
        // Gemessen: in CI bleibt etwa jeder dritte Container nach den drei
        // Bannerzeilen des Einstiegsskripts stumm — `sqlservr` kommt nicht bis
        // zu seiner ersten Protokollzeile, der Container lebt, und nach Ablauf
        // der Frist steht nur `Connection refused`. Speicher- und CPU-Mangel
        // scheiden als Ursache aus: unter Speichermangel stirbt der Server mit
        // hunderten Zeilen Diagnose, unter CPU-Mangel wird er langsam, schreibt
        // aber durchgehend mit. Was bleibt, ist der Plattenzugriff des Runners
        // — nichts, was sich hier nachstellen laesst.
        //
        // Testcontainers verwirft den haengenden Container und startet neu.
        // Das macht aus einem Ausfall in drei Laeufen einen in neun; die
        // Ursache bleibt offen, und die Mitschrift oben bleibt der Weg, sie zu
        // finden.
        withStartupAttempts(2)
    }

    override fun start() {
        try {
            super.start()
        } catch (failure: RuntimeException) {
            throw IllegalStateException(
                "SQL Server wurde binnen ${MSSQL_STARTUP_TIMEOUT.toMinutes()} Minuten nicht " +
                    "erreichbar. " + output.describe(),
                failure,
            )
        }
    }
}

/** Erzeugt den SQL-Server-Container der Tests. */
fun newMssqlContainer(): MssqlTestContainer = MssqlTestContainer()

/**
 * Haelt Anfang und Ende der Container-Ausgabe. Kein voller Mitschnitt: ein
 * SQL-Server-Start schreibt viel, und im Fehlerfall zaehlen die Raender.
 */
private class TailBuffer : Consumer<OutputFrame> {

    private val head = ArrayList<String>(LOG_EDGE_LINES)
    private val tail = ArrayDeque<String>()
    private var dropped = 0

    override fun accept(frame: OutputFrame) {
        val text = frame.utf8String?.trimEnd('\n', '\r') ?: return
        if (text.isEmpty()) return
        synchronized(head) {
            if (head.size < LOG_EDGE_LINES) {
                head += text
                return
            }
            tail.addLast(text)
            if (tail.size > LOG_EDGE_LINES) {
                tail.removeFirst()
                dropped++
            }
        }
    }

    fun describe(): String = synchronized(head) {
        if (head.isEmpty()) {
            return "Er hat dabei nichts ausgegeben — dann lag es nicht an ihm, " +
                "sondern am Start des Containers selbst."
        }
        val middle = if (dropped > 0) listOf("… $dropped weitere Zeilen …") else emptyList()
        "Seine Ausgabe:\n" + (head + middle + tail).joinToString("\n")
    }
}
