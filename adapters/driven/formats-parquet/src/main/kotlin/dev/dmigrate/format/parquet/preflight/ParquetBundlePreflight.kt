package dev.dmigrate.format.parquet.preflight

import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.manifest.ManifestSchemaSource
import dev.dmigrate.format.parquet.manifest.ParquetBundleManifest
import dev.dmigrate.format.parquet.manifest.ParquetManifestParseException
import dev.dmigrate.format.parquet.manifest.ParquetManifestReader
import dev.dmigrate.format.parquet.manifest.Sha256DigestCalculator
import dev.dmigrate.format.parquet.manifest.toChunkSchema
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * AP7 §9.1 Bundle-Preflight (S5a Cut A). Laeuft genau einmal vor dem
 * Streaming-Import und liefert ein [ResolvedParquetBundle] oder wirft
 * eine [ParquetBundlePreflightException] mit den
 * AP7 §9.2-Fehlercodes.
 *
 * Schritte (AP7 §9.1):
 * 1. Bundle-Verzeichnis existiert, regulaer, lesbar.
 * 2. `manifest.yaml` existiert, parsebar (snakeyaml-engine ohne
 *    Anchors/Tags — `ParquetManifestReader` setzt die Flags).
 * 3. `formatVersion` kompatibel (§8.1 MAJOR-Pruefung).
 * 4. `producer == "d-migrate"` — Fremd-Bundles werden markiert, nicht
 *    abgelehnt.
 * 5. Pflichtfelder pro Tabelle (ManifestReader-Validierung) + `tables`
 *    nicht leer.
 * 6. Kollisionsschutz K1-K5 (AP7 §6.2).
 * 7. `schemaSource` Enum-Wert (ManifestReader-Validierung).
 * 8. Optionale SHA-256-Werte pruefen (`MANIFEST_SHA256_MISMATCH`).
 */
class ParquetBundlePreflight {

    /**
     * Liefert das adapter-interne [ResolvedParquetBundle]. CLI-Resolver
     * uebergibt es an den `ParquetBundleAdapter`, der es in das
     * port-eigene `ImportInput.ResolvedBundle` uebersetzt.
     */
    internal fun run(
        bundleRoot: Path,
        tableFilter: List<String>? = null,
        tableOrder: List<String>? = null,
    ): ResolvedParquetBundle {
        // Schritt 1
        if (!Files.isDirectory(bundleRoot)) {
            throw ParquetBundlePreflightException(
                "MANIFEST_FILE_OUTSIDE_BUNDLE: bundleRoot is not a directory: $bundleRoot",
            )
        }
        val manifestPath = bundleRoot.resolve(MANIFEST_FILE_NAME)
        if (!Files.isRegularFile(manifestPath)) {
            throw ParquetBundlePreflightException(
                "MANIFEST_NOT_FOUND: $manifestPath",
            )
        }

        // Schritt 2-5,7 (Reader-Validierung)
        val manifest = parseManifest(manifestPath)

        // Schritt 3 (MAJOR-Pruefung — wir lesen heute nur 1.x)
        val (major, _) = parseFormatVersion(manifest.formatVersion)
        if (major != SUPPORTED_MAJOR) {
            throw ParquetBundlePreflightException(
                "MANIFEST_VERSION_INCOMPATIBLE: bundle formatVersion='${manifest.formatVersion}' " +
                    "exceeds supported MAJOR=$SUPPORTED_MAJOR",
            )
        }

        // Schritt 6 — Kollisionsschutz
        runCollisionChecks(manifest, bundleRoot)

        // Tabellenfilter + Order (AP8 §4.4)
        val effectiveTables = applyFilterAndOrder(manifest, tableFilter, tableOrder)

        // Schritt 8 — Optional SHA-256
        for (table in effectiveTables) {
            val expected = table.sha256 ?: continue
            val actual = Sha256DigestCalculator.compute(bundleRoot.resolve(table.file))
            if (!expected.equals(actual, ignoreCase = true)) {
                throw ParquetBundlePreflightException(
                    "MANIFEST_SHA256_MISMATCH: table='${table.table}' file='${table.file}' " +
                        "expected=$expected actual=$actual",
                )
            }
        }

        val origin = when (manifest.schemaSource) {
            ManifestSchemaSource.SCHEMA_READER -> SchemaOrigin.SCHEMA_READER
            ManifestSchemaSource.JDBC_METADATA -> SchemaOrigin.JDBC_METADATA
            ManifestSchemaSource.MANIFEST_FALLBACK -> SchemaOrigin.MANIFEST_FALLBACK
        }

        val bindings = effectiveTables.map { table ->
            val absolutePath = bundleRoot.resolve(table.file).toAbsolutePath().normalize()
            ResolvedParquetTableBinding(
                table = table.table,
                path = absolutePath,
                schema = table.toChunkSchema(origin),
                expectedSha256 = table.sha256,
            )
        }

        val manifestSha256 = computeManifestDigest(manifestPath)

        return ResolvedParquetBundle(
            bundleRoot = bundleRoot.toAbsolutePath().normalize(),
            manifestSha256 = manifestSha256,
            formatVersion = manifest.formatVersion,
            producerVersion = manifest.producerVersion,
            schemaSource = manifest.schemaSource.yamlValue,
            tables = bindings,
        )
    }

    private fun parseManifest(manifestPath: Path): ParquetBundleManifest {
        return try {
            Files.newInputStream(manifestPath).use { stream ->
                ParquetManifestReader(context = ParquetManifestReader.Context.BUNDLE).read(stream)
            }
        } catch (ex: ParquetManifestParseException) {
            throw ParquetBundlePreflightException(ex.message ?: "MANIFEST_PARSE_ERROR", ex)
        } catch (ex: Exception) {
            throw ParquetBundlePreflightException("MANIFEST_PARSE_ERROR: ${ex.message}", ex)
        }
    }

    private fun runCollisionChecks(manifest: ParquetBundleManifest, bundleRoot: Path) {
        val seenTables = mutableSetOf<String>()
        val seenFiles = mutableSetOf<String>()
        for (table in manifest.tables) {
            if (!seenTables.add(table.table)) {
                throw ParquetBundlePreflightException(
                    "MANIFEST_TABLE_DUPLICATE: table='${table.table}' appears more than once",
                )
            }
            if (!seenFiles.add(table.file)) {
                throw ParquetBundlePreflightException(
                    "MANIFEST_FILE_DUPLICATE: file='${table.file}' is referenced by multiple tables",
                )
            }
            val resolved = bundleRoot.resolve(table.file).toAbsolutePath().normalize()
            if (!resolved.startsWith(bundleRoot.toAbsolutePath().normalize())) {
                throw ParquetBundlePreflightException(
                    "MANIFEST_FILE_OUTSIDE_BUNDLE: file='${table.file}' resolves outside bundle",
                )
            }
            if (!Files.isRegularFile(resolved)) {
                throw ParquetBundlePreflightException(
                    "MANIFEST_FILE_MISSING: file='${table.file}' does not exist or is not regular",
                )
            }
        }

        // K5 — Parquet-Files im Bundle, die NICHT im Manifest sind.
        val manifestFileNames = manifest.tables.map { it.file }.toSet()
        val orphanFiles = Files.list(bundleRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(PARQUET_EXTENSION) }
                .map { bundleRoot.relativize(it).toString() }
                .filter { it !in manifestFileNames }
                .toList()
        }
        if (orphanFiles.isNotEmpty()) {
            throw ParquetBundlePreflightException(
                "MANIFEST_FILE_UNREFERENCED: parquet files in bundle but not in manifest: " +
                    orphanFiles.sorted().joinToString(),
            )
        }
    }

    private fun applyFilterAndOrder(
        manifest: ParquetBundleManifest,
        tableFilter: List<String>?,
        tableOrder: List<String>?,
    ): List<dev.dmigrate.format.parquet.manifest.ManifestTable> {
        val byTable = manifest.tables.associateBy { it.table }
        val filtered = if (tableFilter != null) {
            val missing = tableFilter.filterNot { it in byTable }
            require(missing.isEmpty()) {
                "MANIFEST_FILE_MISSING: tableFilter references unknown tables: ${missing.joinToString()}"
            }
            tableFilter.mapNotNull { byTable[it] }
        } else {
            manifest.tables
        }
        return if (tableOrder != null) {
            val byName = filtered.associateBy { it.table }
            val missing = tableOrder.filterNot { it in byName }
            require(missing.isEmpty()) {
                "MANIFEST_FILE_MISSING: tableOrder references unknown tables: ${missing.joinToString()}"
            }
            tableOrder.mapNotNull { byName[it] }
        } else {
            filtered
        }
    }

    private fun parseFormatVersion(version: String): Pair<Int, Int> {
        val parts = version.split(".")
        require(parts.size == 2) { "MANIFEST_FIELD_INVALID: formatVersion='$version' is not MAJOR.MINOR" }
        return parts[0].toInt() to parts[1].toInt()
    }

    private fun computeManifestDigest(manifestPath: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(manifestPath).use { stream ->
            val buffer = ByteArray(DIGEST_BUFFER)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        const val MANIFEST_FILE_NAME: String = "manifest.yaml"
        private const val PARQUET_EXTENSION = ".parquet"
        private const val SUPPORTED_MAJOR = 1
        private const val DIGEST_BUFFER = 8 * 1024
    }
}

/**
 * AP7 §9.2-Fehlerklasse. `message`-Zeile beginnt mit dem stabilen
 * Fehlercode (`MANIFEST_*`).
 */
class ParquetBundlePreflightException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
