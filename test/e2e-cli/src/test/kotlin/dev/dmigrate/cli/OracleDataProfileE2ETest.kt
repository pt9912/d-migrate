package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * `data profile` erreicht fuer oracle den Verbindungsaufbau — containerlos
 * belegt.
 *
 * Bis zum Profiling-Modul wies ein Kommando-Gate den Dialekt an der
 * Kommando-Grenze ab, mit Exit 2 und einer Meldung, bevor irgendeine
 * Verbindung versucht wurde. Genau das darf jetzt **nicht** mehr passieren:
 * die URL zeigt auf einen Port, an dem niemand lauscht, der Lauf muss also
 * an der Verbindung scheitern und nicht an einer Kommando-Grenze.
 *
 * Ohne diese Zusicherung waere ein wiedereingefuehrtes Gate — oder eine
 * Adapter-Auswahl, die oracle weiterhin ablehnt — von aussen nicht von einem
 * funktionierenden Pfad zu unterscheiden.
 */
class OracleDataProfileE2ETest : FunSpec({

    test("data profile against oracle reaches the connection attempt instead of a command-boundary refusal") {
        val run = runRealCli(listOf("data", "profile", "--source", UNREACHABLE_ORACLE_URL))
        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            // Exit 4 ist der Verbindungsfehler -- der Lauf ist bis dorthin
            // gekommen. Exit 2 waere der Usage-/Config-Fehler, mit dem die
            // Kommando-Grenze abwies.
            run.exitCode shouldBe 4
            run.stderr shouldNotContain "does not support dialect oracle"
        }
    }
})

/** Port 1 lauscht nirgends — der Lauf muss hier scheitern, nicht vorher. */
private const val UNREACHABLE_ORACLE_URL = "oracle://app:Profile_E2E_Pa55word@127.0.0.1:1/orclpdb1"
