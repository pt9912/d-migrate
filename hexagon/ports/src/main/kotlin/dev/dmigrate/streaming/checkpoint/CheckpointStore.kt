package dev.dmigrate.streaming.checkpoint

/**
 * 0.9.0 Phase B (`docs/ImpPlan-0.9.0-B.md` §4.1): expliziter Port fuer
 * Checkpoint-Persistenz. Runner und Streaming-Klassen sprechen ausschliess-
 * lich gegen diesen Port; die konkrete Speicherform (Dateisystem in 0.9.0,
 * spaeter ggf. andere Adapter) bleibt austauschbar.
 *
 * Phase B stellt den Vertrag und den dateibasierten Erstadapter bereit.
 * Der tatsaechliche Resume-Flow (Manifest-Fortschreibung waehrend des
 * Streams, Wiederaufnahme) ist Phasen C/D vorbehalten.
 */
interface CheckpointStore {

    /**
     * Laedt das Manifest fuer die gegebene [operationId]. Null, wenn
     * keins existiert.
     *
     * @throws UnsupportedCheckpointVersionException wenn das gespeicherte
     *   Manifest eine nicht unterstuetzte [CheckpointManifest.schemaVersion]
     *   traegt — Phase-A-Exit-Code-Vertrag §4.5 (Exit 7).
     * @throws CheckpointStoreException fuer unlesbare/partielle Dateien
     *   (Exit 7 am CLI-Rand).
     */
    fun load(operationId: String): CheckpointManifest?

    /**
     * Persistiert das Manifest atomar. Der Adapter ersetzt die Zieldatei
     * entweder vollstaendig oder schlaegt mit [CheckpointStoreException]
     * fehl (Phase B §4.6); partielle Ueberschreibungen sind nicht
     * zulaessig.
     */
    fun save(manifest: CheckpointManifest)

    /**
     * Listet verfuegbare Checkpoints. Reihenfolge ist nicht garantiert.
     * Adapter muessen tolerant gegenueber fremden Dateien im Checkpoint-
     * Verzeichnis sein.
     */
    fun list(): List<CheckpointReference>

    /**
     * Markiert einen Lauf als abgeschlossen. In 0.9.0 ist die Default-
     * Umsetzung "Datei entfernen"; spaetere Milestones koennen das zu
     * einem Archivierungsmodell erweitern (§4.6, Nicht-Ziel: endgueltiges
     * Housekeeping).
     */
    fun complete(operationId: String)
}

/**
 * Leichtgewichtige Referenz auf einen gespeicherten Checkpoint. Adapter
 * liefern den Kern-Header, ohne das gesamte Manifest deserialisieren zu
 * muessen, damit UI/Listings ohne vollen Disk-Load auskommen.
 */
data class CheckpointReference(
    val operationId: String,
    val operationType: CheckpointOperationType,
    val schemaVersion: Int,
)

/**
 * 0.9.0 Phase B §4.1 / §4.6: generischer Fehlerkanal des Ports.
 * Adapter (z.B. der dateibasierte Store) werfen diesen Typ bei unlesbaren,
 * partiellen oder strukturell defekten Manifesten. Der CLI-Rand mappt das
 * in Phase C/D auf Exit 7 (Phase A §4.5 / Runner-KDoc).
 */
class CheckpointStoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 0.9.0 Phase B §4.2: wird geworfen, wenn die gespeicherte
 * [CheckpointManifest.schemaVersion] nicht mit
 * [CheckpointManifest.CURRENT_SCHEMA_VERSION] kompatibel ist. Gedacht fuer
 * klare Fehlermeldungen beim Laden aelterer/neuerer Manifeste.
 */
class UnsupportedCheckpointVersionException(
    val foundVersion: Int,
    val supportedVersion: Int = CheckpointManifest.CURRENT_SCHEMA_VERSION,
) : RuntimeException(
    "Checkpoint manifest schemaVersion=$foundVersion is not supported " +
        "by this build (supported: $supportedVersion)."
)
