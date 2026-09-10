package dev.dmigrate.driver

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.connection.ConnectionPool

/**
 * Wendet ein Soll-Schema in einem **Wegwerf-Schema** auf demselben Server an
 * und liest zurueck, welche Form der Server daraus macht.
 *
 * Der Zweck ist der Vergleich rohen SQL-Texts. Autorentext und Katalogform
 * stimmen nie ueberein — der Server druckt aus seinem Parsebaum —, zwei
 * Katalogformen dagegen schon. Wo keine Herkunft vorliegt, ist das der einzige
 * Weg, gleich mit gleich zu vergleichen, ohne das Ziel anzufassen.
 *
 * **Kein Ergebnis ist ein zulaessiges Ergebnis.** Fehlende Rechte, ein Dialekt
 * ohne Wegwerf-Schema, ein Soll, das sich dort nicht anwenden laesst: dann
 * [RawTextSandboxResult.Unavailable], und der Vergleich entscheidet wie bisher
 * am Text — konservativ. Ein Sandkasten, der raet, waere schlimmer als keiner.
 *
 * Warum es nicht ging, wird dabei **genannt**: wer den Sandkasten eingeschaltet
 * hat, soll erfahren, wenn er nie greift, statt sich ueber unveraendertes
 * Verhalten zu wundern.
 */
interface RawTextSandboxPort {

    fun deparse(
        desired: SchemaDefinition,
        pool: ConnectionPool,
        options: SchemaReadOptions = SchemaReadOptions(),
    ): RawTextSandboxResult
}

/** Was der Sandkasten hergab — oder warum nichts. */
sealed interface RawTextSandboxResult {

    /** Das Soll, wie der Server es fuehrt. */
    data class Deparsed(val schema: SchemaDefinition) : RawTextSandboxResult

    /** Kein Ergebnis, mit Grund. */
    data class Unavailable(val reason: String) : RawTextSandboxResult
}
