package dev.dmigrate.streaming.checkpoint

import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointOperationType
import dev.dmigrate.streaming.checkpoint.CheckpointReference
import dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import dev.dmigrate.streaming.checkpoint.UnsupportedCheckpointVersionException
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * 0.9.0 Phase B (`docs/ImpPlan-0.9.0-B.md` §4.1 / §4.6): dateibasierter
 * Erstadapter fuer den [CheckpointStore]-Port. Serialisiert [CheckpointManifest]
 * als kompaktes YAML-Dokument.
 *
 * **Atomarer Schreibpfad (§4.6)**: `save()` schreibt zuerst in eine
 * `<operationId>.checkpoint.yaml.tmp`-Datei und ersetzt die Zieldatei
 * anschliessend atomar per `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`.
 * Dadurch kann ein abgebrochener Schreiber keine halb-valide
 * Resume-Quelle hinterlassen.
 *
 * **Dateilayout**: pro Lauf genau eine Datei
 * `<directory>/<operationId>.checkpoint.yaml`. Temp-/Fremddateien im
 * Verzeichnis werden ignoriert.
 */
class FileCheckpointStore(
    /**
     * Zielverzeichnis fuer Checkpoint-Dateien. Wird beim ersten
     * [save]-Aufruf angelegt, falls es noch nicht existiert. Der Caller
     * (Phase C/D) mergt CLI- und Config-Werte vorher ueber
     * [dev.dmigrate.streaming.CheckpointConfig.merge].
     */
    private val directory: Path,
) : CheckpointStore {

    private val dump: Dump = Dump(
        DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .build()
    )
    private val load: Load = Load(LoadSettings.builder().build())

    override fun load(operationId: String): CheckpointManifest? {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        val path = manifestPath(operationId)
        if (!Files.isRegularFile(path)) return null
        val raw = try {
            Files.newInputStream(path).use { load.loadFromInputStream(it) }
        } catch (t: Throwable) {
            throw CheckpointStoreException(
                "Failed to parse checkpoint manifest at $path: ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }
        val map = raw as? Map<*, *>
            ?: throw CheckpointStoreException(
                "Checkpoint manifest at $path is not a YAML mapping"
            )
        return fromMap(map, path)
    }

    override fun save(manifest: CheckpointManifest) {
        Files.createDirectories(directory)
        val target = manifestPath(manifest.operationId)
        val temp = target.resolveSibling("${target.fileName}.tmp")
        val yaml = dump.dumpToString(toMap(manifest))
        try {
            Files.writeString(temp, yaml)
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Fallback auf REPLACE_EXISTING ohne ATOMIC_MOVE — relevant
                // auf manchen Windows-Shared-Mounts; die Kante ist fuer
                // 0.9.0 dokumentiert und bleibt "atomar nach bestem
                // Vermoegen des Dateisystems". Partial-File-Schutz bleibt
                // durch temp-first-pattern erhalten.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            Files.deleteIfExists(temp)
            throw CheckpointStoreException(
                "Failed to atomically write checkpoint manifest to $target: " +
                    (t.message ?: t::class.simpleName),
                cause = t,
            )
        }
    }

    override fun list(): List<CheckpointReference> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.toList()
                .filter { it.fileName.toString().endsWith(MANIFEST_SUFFIX) }
                .mapNotNull { path ->
                    try {
                        val manifest = loadManifestForRef(path)
                        CheckpointReference(
                            operationId = manifest.operationId,
                            operationType = manifest.operationType,
                            schemaVersion = manifest.schemaVersion,
                        )
                    } catch (_: CheckpointStoreException) {
                        // Fremde/defekte Dateien ueberspringen; list() darf
                        // nicht wegen einer korrupten Datei scheitern.
                        null
                    }
                }
        }
    }

    override fun complete(operationId: String) {
        val path = manifestPath(operationId)
        try {
            Files.deleteIfExists(path)
        } catch (t: Throwable) {
            throw CheckpointStoreException(
                "Failed to remove completed checkpoint at $path: " +
                    (t.message ?: t::class.simpleName),
                cause = t,
            )
        }
    }

    private fun manifestPath(operationId: String): Path =
        directory.resolve("$operationId$MANIFEST_SUFFIX")

    private fun loadManifestForRef(path: Path): CheckpointManifest {
        val raw = Files.newInputStream(path).use { load.loadFromInputStream(it) }
        val map = raw as? Map<*, *>
            ?: throw CheckpointStoreException("Checkpoint manifest at $path is not a YAML mapping")
        return fromMap(map, path)
    }

    private fun toMap(manifest: CheckpointManifest): Map<String, Any?> = buildMap {
        put("schemaVersion", manifest.schemaVersion)
        put("operationId", manifest.operationId)
        put("operationType", manifest.operationType.name)
        put("createdAt", manifest.createdAt.toString())
        put("updatedAt", manifest.updatedAt.toString())
        put("format", manifest.format)
        put("chunkSize", manifest.chunkSize)
        put("optionsFingerprint", manifest.optionsFingerprint)
        put(
            "tableSlices",
            manifest.tableSlices.map { slice ->
                linkedMapOf<String, Any?>(
                    "table" to slice.table,
                    "status" to slice.status.name,
                    "rowsProcessed" to slice.rowsProcessed,
                    "chunksProcessed" to slice.chunksProcessed,
                    "lastMarker" to slice.lastMarker,
                )
            },
        )
    }

    private fun fromMap(map: Map<*, *>, path: Path): CheckpointManifest {
        val schemaVersion = (map["schemaVersion"] as? Number)?.toInt()
            ?: throw CheckpointStoreException("Checkpoint manifest at $path is missing 'schemaVersion'")
        if (schemaVersion != CheckpointManifest.CURRENT_SCHEMA_VERSION) {
            throw UnsupportedCheckpointVersionException(foundVersion = schemaVersion)
        }
        val operationId = (map["operationId"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw CheckpointStoreException("Checkpoint manifest at $path is missing 'operationId'")
        val operationType = parseOperationType(map["operationType"], path)
        val createdAt = parseInstant(map["createdAt"], "createdAt", path)
        val updatedAt = parseInstant(map["updatedAt"], "updatedAt", path)
        val chunkSize = (map["chunkSize"] as? Number)?.toInt()
            ?: throw CheckpointStoreException("Checkpoint manifest at $path is missing 'chunkSize'")
        val slicesRaw = map["tableSlices"] as? List<*> ?: emptyList<Any?>()
        val slices = slicesRaw.mapNotNull { raw ->
            val slice = raw as? Map<*, *> ?: return@mapNotNull null
            val table = slice["table"] as? String ?: return@mapNotNull null
            CheckpointTableSlice(
                table = table,
                status = parseSliceStatus(slice["status"], path),
                rowsProcessed = (slice["rowsProcessed"] as? Number)?.toLong() ?: 0L,
                chunksProcessed = (slice["chunksProcessed"] as? Number)?.toLong() ?: 0L,
                lastMarker = slice["lastMarker"] as? String,
            )
        }
        return CheckpointManifest(
            schemaVersion = schemaVersion,
            operationId = operationId,
            operationType = operationType,
            createdAt = createdAt,
            updatedAt = updatedAt,
            format = map["format"] as? String,
            chunkSize = chunkSize,
            tableSlices = slices,
            optionsFingerprint = map["optionsFingerprint"] as? String,
        )
    }

    private fun parseOperationType(value: Any?, path: Path): CheckpointOperationType {
        val raw = value as? String
            ?: throw CheckpointStoreException("Checkpoint manifest at $path has missing/invalid 'operationType'")
        return runCatching { CheckpointOperationType.valueOf(raw) }.getOrElse {
            throw CheckpointStoreException(
                "Checkpoint manifest at $path has unknown operationType '$raw'"
            )
        }
    }

    private fun parseSliceStatus(value: Any?, path: Path): CheckpointSliceStatus {
        val raw = value as? String
            ?: throw CheckpointStoreException("Checkpoint manifest at $path has slice without 'status'")
        return runCatching { CheckpointSliceStatus.valueOf(raw) }.getOrElse {
            throw CheckpointStoreException(
                "Checkpoint manifest at $path has unknown slice status '$raw'"
            )
        }
    }

    private fun parseInstant(value: Any?, field: String, path: Path): Instant {
        val raw = value as? String
            ?: throw CheckpointStoreException("Checkpoint manifest at $path has missing/invalid '$field'")
        return runCatching { Instant.parse(raw) }.getOrElse {
            throw CheckpointStoreException(
                "Checkpoint manifest at $path has non-ISO '$field' value '$raw'"
            )
        }
    }

    companion object {
        const val MANIFEST_SUFFIX: String = ".checkpoint.yaml"
    }
}
