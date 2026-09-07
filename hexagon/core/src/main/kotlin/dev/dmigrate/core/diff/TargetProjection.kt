package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig

/**
 * Die verlustbehafteten Projektionen des **Ziel**-Dialekts, durch die der
 * ziel-bewusste Vergleich beide Seiten schickt.
 *
 * Der Vergleich stellt ein handgeschriebenes Soll gegen ein
 * zurueckgelesenes Ist. Wo der Zielserver eine Angabe gar nicht ablegt,
 * kann sein Reverse sie nicht zurueckgeben — die beiden Seiten
 * unterschieden sich dann bei **jedem** Lauf, und der Planer plante
 * dieselbe Aenderung erneut. Beide Seiten durch dieselbe verlustbehaftete
 * Funktion zu schicken macht gleich, was der Dialekt nicht unterscheiden
 * kann.
 *
 * `null` als Bundle heisst **strikter** Vergleich: `schema compare` soll
 * jeden Unterschied zeigen, auch einen, den das Ziel nicht ausdruecken
 * kann.
 *
 * **Was hier NICHT hingehoert:** Felder, die generell nicht semantisch sind
 * (etwa die Volltext-Rekonstruktionshinweise). Die blendet
 * [TableComparator] fuer jeden Dialekt aus, und sie stehen im
 * Drei-Projektionen-Vertrag mit `MigrationFingerprint` und
 * `CanonicalPayload`. Hier geht es um die andere Achse: ein Feld ist
 * semantisch, nur dieser eine Dialekt kann es nicht zurueckmelden.
 *
 * Deshalb wirkt die Projektion **ausschliesslich auf die
 * Vergleichsentscheidung**. Die geplante Operation traegt weiterhin die
 * unveraenderten Definitionen — sonst flosse eine Vergleichs-Projektion in
 * die erzeugte DDL, und die Operations-ID (`CanonicalPayload`) aenderte
 * sich, was bestehende Overlays entwertete.
 */
data class TargetProjection(
    /** Typen, die das Ziel auf denselben deklarierten Typ faltet. */
    val type: (NeutralType) -> NeutralType,
    /**
     * Index-Eigenschaften, die das Ziel nicht fuehrt — etwa die
     * Text-Search-Konfiguration, `INCLUDE`-Spalten, die Bitmap-Zugriffsart
     * oder der Name eines Volltext-Index, den der Reverse synthetisiert.
     */
    val index: (IndexDefinition) -> IndexDefinition = { it },
    /**
     * Die Erzeugungsart einer Spalte — vor allem der system-vergebene
     * Sequenzname einer Identity-Spalte, den ein Soll-Schema nicht tragen
     * kann.
     */
    val generation: (ColumnGeneration?) -> ColumnGeneration? = { it },
    /**
     * Partitionsangaben, die das Ziel nicht fuehrt — Modulus und Remainder
     * einer HASH-Partition, und die Faltung eines Zeitstempels um
     * Mitternacht auf das reine Datum, wo der Dialekt beides nicht
     * unterscheidet.
     *
     * Die **untere** RANGE-Grenze braucht sie nicht: die leitet
     * [dev.dmigrate.core.model.PartitionBoundNormalizer] fuer jeden Dialekt
     * aus der Kontiguitaet ab, und die Reverse-Leser von MySQL und SQL
     * Server tun dasselbe.
     *
     * Faltet eine Projektion die untere Grenze dennoch weg (Oracle tut es),
     * laeuft die Ableitung danach auch auf der **verfassten** Seite, wo eine
     * angegebene Grenze sie zuvor uebersprang. Eine vom Autor gelassene
     * Luecke vergleicht sich dann gleich mit einem lueckenlosen Ist — was
     * dem Fingerabdruck unter derselben Projektion entspricht, und was
     * Oracle ohnehin nicht ablegen kann.
     */
    val partitioning: (PartitionConfig) -> PartitionConfig = { it },
)
