package dev.dmigrate.format.data

import java.nio.file.Path

/**
 * Quellabstraktion fuer den seekbaren Reader-Pfad (AP10 §3.2,
 * `docs/planning/done/parquet-port-shape.md`). Sealed mit
 * heute genau einem Subtyp [Local]; weitere Varianten
 * (Object-Storage, gemounteter Cache) erweitern die Hierarchie
 * additiv im selben Modul.
 *
 * **Bewusst Sealed:** Kotlin laesst direkte Sealed-Subtypen
 * nur im selben Modul zu. Ein externer Object-Storage-Adapter
 * kann den Vertrag deshalb nicht selbst implementieren —
 * neue Varianten landen in dieser Datei und ein exhaustive
 * `when` in Konsumenten bricht beim Sealed-Sweep (AP10 §3.2).
 *
 * `InputStream`-Quellen werden **nicht** in eine Sealed-
 * Variante uebersetzt (`parquet-libraries.md` §7 Bullet 2 —
 * kein impliziter Temp-Spool); fuer JSON/YAML/CSV bleibt der
 * heutige [DataChunkReaderFactory]-Pfad zustaendig.
 */
sealed interface SeekableChunkSource {

    /**
     * Lokales Dateisystem. Der Reader erhaelt einen regulaeren
     * [Path]; die Lifecycle-Verantwortung liegt beim Aufrufer
     * (Bundle-Resolver oeffnet/schliesst den Reader, der Path
     * selbst hat keinen Lifecycle).
     */
    data class Local(val path: Path) : SeekableChunkSource
}
