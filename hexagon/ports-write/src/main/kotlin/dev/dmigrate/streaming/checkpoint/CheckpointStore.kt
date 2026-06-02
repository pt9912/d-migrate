package dev.dmigrate.streaming.checkpoint

/**
 * LF-013 / LN-012 / LN-013: expliziter Port fuer Checkpoint-Persistenz.
 * Runner und Streaming-Klassen sprechen ausschliess-
 * lich gegen diesen Port; die konkrete Speicherform (Dateisystem in 0.9.0,
 * spaeter ggf. andere Adapter) bleibt austauschbar.
 */
interface CheckpointStore {

    /**
     * Laedt das Manifest fuer die gegebene [operationId]. Null, wenn
     * keins existiert.
     *
     * @throws UnsupportedCheckpointVersionException wenn das gespeicherte
     *   Manifest eine nicht unterstuetzte [CheckpointManifest.schemaVersion]
     *   traegt — CLI-Exit 7.
     * @throws CheckpointStoreException fuer unlesbare/partielle Dateien
     *   (Exit 7 am CLI-Rand).
     */
    fun load(operationId: String): CheckpointManifest?

    /**
     * Persistiert das Manifest atomar. Der Adapter ersetzt die Zieldatei
     * entweder vollstaendig oder schlaegt mit [CheckpointStoreException]
     * fehl; partielle Ueberschreibungen sind nicht zulaessig.
     */
    fun save(manifest: CheckpointManifest)

    /**
     * Listet verfuegbare Checkpoints. Reihenfolge ist nicht garantiert.
     * Adapter muessen tolerant gegenueber fremden Dateien im Checkpoint-
     * Verzeichnis sein.
     */
    fun list(): List<CheckpointReference>

    /**
     * Markiert einen Lauf als abgeschlossen. Die Default-Umsetzung
     * "Datei entfernen" kann spaeter durch ein Archivierungsmodell
     * ersetzt werden.
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
 * LF-013 / LN-012 / LN-013: generischer Fehlerkanal des Ports.
 * Adapter (z.B. der dateibasierte Store) werfen diesen Typ bei unlesbaren,
 * partiellen oder strukturell defekten Manifesten. Der CLI-Rand mappt das
 * auf Exit 7.
 */
class CheckpointStoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * LF-013 / LN-012: wird geworfen, wenn die gespeicherte
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
