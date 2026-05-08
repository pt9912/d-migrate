package dev.dmigrate.mcp.registry

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.server.ports.JobWorkerOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * LF-010 / LF-013 / LN-009 / LN-011 Part 2 — Acceptance-Tests für [BundleImportPipeline].
 *
 * Aus dem Worker herausgelöste Bundle-Logik. Tests benutzen echte
 * ZIP-Bytes (kein Mocking des Extractors) und eine Test-Lambda für
 * `importTable`, die in einer Liste protokolliert wird.
 */
class BundleImportPipelineTest : FunSpec({

    fun bundleBytes(builder: ZipOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use(builder)
        return bos.toByteArray()
    }

    fun ZipOutputStream.writeEntry(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    fun ZipOutputStream.writeManifest(json: String) =
        writeEntry("manifest.json", json.toByteArray(Charsets.UTF_8))

    fun simpleManifest(): String =
        """
        {
          "version": "v1",
          "format": "csv",
          "tables": [
            {"name": "users",  "path": "users.csv"},
            {"name": "orders", "path": "orders/orders.csv"}
          ]
        }
        """.trimIndent()

    /**
     * Schreibt das Bundle-ZIP in ein Temp-File, allokiert ein
     * Bundle-Root-Verzeichnis und liefert beide Pfade. Aufrufer
     * räumt ZIP via try/finally weg; Pipeline löscht
     * Bundle-Root rekursiv im finally-Pfad.
     */
    fun setupTempBundle(zipBytes: ByteArray): Pair<Path, Path> {
        val zip = Files.createTempFile("pipeline-bundle-", ".zip")
        Files.write(zip, zipBytes)
        val root = Files.createTempDirectory("pipeline-bundle-root-")
        return zip to root
    }

    fun cleanupZip(zip: Path) {
        Files.deleteIfExists(zip)
    }

    val noOpCancel: CancellationToken = CancellationToken.none()

    test("Happy-Path: per-Tabelle-Import wird in Manifest-Reihenfolge aufgerufen, Bundle-Root wird gelöscht") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", "id,name\n1,Alice\n".toByteArray())
            writeEntry("orders/orders.csv", "id,user_id\n1,1\n".toByteArray())
        }
        val (zip, root) = setupTempBundle(bytes)
        val invocations = mutableListOf<Triple<String, String, String>>()
        try {
            val outcome = BundleImportPipeline().execute(
                bundleZip = zip,
                bundleRoot = root,
                callerTables = listOf("users", "orders"),
                cancellationToken = noOpCancel,
                importTable = { source, table, format ->
                    invocations += Triple(source.fileName.toString(), table, format)
                    JobWorkerOutcome.Succeeded()
                },
            )
            outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>()
            invocations.size shouldBe 2
            invocations[0].second shouldBe "users"
            invocations[1].second shouldBe "orders"
            invocations.forEach { it.third shouldBe "csv" }
            // Cleanup hat das Bundle-Root gelöscht.
            Files.exists(root) shouldBe false
        } finally {
            cleanupZip(zip)
            // Defensive Cleanup falls Pipeline den Root nicht entfernt hat.
            if (Files.exists(root)) {
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    test("Fehlende Manifest-Konsistenz mit Caller-Tables -> MCP_BUNDLE_INVALID") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", "x".toByteArray())
            writeEntry("orders/orders.csv", "x".toByteArray())
        }
        val (zip, root) = setupTempBundle(bytes)
        val invocations = mutableListOf<String>()
        try {
            val outcome = BundleImportPipeline().execute(
                bundleZip = zip,
                bundleRoot = root,
                // Caller-Tables enthalten "products", die im Manifest fehlen.
                callerTables = listOf("users", "products"),
                cancellationToken = noOpCancel,
                importTable = { _, table, _ ->
                    invocations += table
                    JobWorkerOutcome.Succeeded()
                },
            )
            val failed = outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
            failed.errorCode shouldBe BundleImportPipeline.ERROR_BUNDLE_INVALID
            failed.errorMessage shouldContain "table drift"
            // importTable wurde nicht aufgerufen — Drift wurde vor der
            // Iteration erkannt.
            invocations shouldBe emptyList<String>()
        } finally {
            cleanupZip(zip)
        }
    }

    test("Bundle ohne Manifest -> MCP_BUNDLE_INVALID") {
        val bytes = bundleBytes {
            writeEntry("users.csv", "x".toByteArray())
        }
        val (zip, root) = setupTempBundle(bytes)
        try {
            val outcome = BundleImportPipeline().execute(
                bundleZip = zip,
                bundleRoot = root,
                callerTables = listOf("users"),
                cancellationToken = noOpCancel,
                importTable = { _, _, _ -> JobWorkerOutcome.Succeeded() },
            )
            outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
                .errorCode shouldBe BundleImportPipeline.ERROR_BUNDLE_INVALID
        } finally {
            cleanupZip(zip)
        }
    }

    test("Fail-fast: erste fehlgeschlagene Tabelle bricht Iteration ab") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", "x".toByteArray())
            writeEntry("orders/orders.csv", "x".toByteArray())
        }
        val (zip, root) = setupTempBundle(bytes)
        val invocations = mutableListOf<String>()
        try {
            val outcome = BundleImportPipeline().execute(
                bundleZip = zip,
                bundleRoot = root,
                callerTables = listOf("users", "orders"),
                cancellationToken = noOpCancel,
                importTable = { _, table, _ ->
                    invocations += table
                    if (table == "users") {
                        JobWorkerOutcome.Failed("MCP_DATA_IMPORT_FAILED", "kaboom", 5)
                    } else {
                        JobWorkerOutcome.Succeeded()
                    }
                },
            )
            val failed = outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
            failed.errorCode shouldBe "MCP_DATA_IMPORT_FAILED"
            failed.exitCode shouldBe 5
            // Nur "users" wurde aufgerufen — "orders" wird übersprungen.
            invocations shouldBe listOf("users")
        } finally {
            cleanupZip(zip)
        }
    }

    test("Cancellation: Token-Cancel wird vor jeder Tabelle geprüft (per-Iteration)") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", "x".toByteArray())
            writeEntry("orders/orders.csv", "x".toByteArray())
        }
        val (zip, root) = setupTempBundle(bytes)
        val invocations = mutableListOf<String>()
        var sawUsers = false
        val cancellingToken = object : CancellationToken {
            override val isCancellationRequested: Boolean
                get() = sawUsers
            override val cancellationReason: String? = "cancelled"
            override fun throwIfCancellationRequested() {
                check(!sawUsers) { "cancelled" }
            }
        }
        try {
            io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                BundleImportPipeline().execute(
                    bundleZip = zip,
                    bundleRoot = root,
                    callerTables = listOf("users", "orders"),
                    cancellationToken = cancellingToken,
                    importTable = { _, table, _ ->
                        invocations += table
                        if (table == "users") sawUsers = true
                        JobWorkerOutcome.Succeeded()
                    },
                )
            }
            invocations shouldBe listOf("users")
        } finally {
            cleanupZip(zip)
        }
    }

    test("Cleanup: Bundle-Root wird auch bei Pipeline-Exception entfernt") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", "x".toByteArray())
            writeEntry("orders/orders.csv", "x".toByteArray())
        }
        val (zip, root) = setupTempBundle(bytes)
        try {
            io.kotest.assertions.throwables.shouldThrow<RuntimeException> {
                BundleImportPipeline().execute(
                    bundleZip = zip,
                    bundleRoot = root,
                    callerTables = listOf("users", "orders"),
                    cancellationToken = noOpCancel,
                    importTable = { _, _, _ -> error("simulated failure") },
                )
            }
            Files.exists(root) shouldBe false
        } finally {
            cleanupZip(zip)
        }
    }

    test("Manifest mit Eintrag, dessen Datei der Extractor nicht extrahiert hat -> MCP_BUNDLE_INVALID") {
        // Konstruieren wir ein Bundle, in dem das Manifest auf eine Datei
        // verweist, die nicht im ZIP liegt — der BundleExtractor wirft
        // dann selbst Invalid; die Pipeline mappt das zu MCP_BUNDLE_INVALID.
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", "x".toByteArray())
            // orders/orders.csv fehlt absichtlich.
        }
        val (zip, root) = setupTempBundle(bytes)
        try {
            val outcome = BundleImportPipeline().execute(
                bundleZip = zip,
                bundleRoot = root,
                callerTables = listOf("users", "orders"),
                cancellationToken = noOpCancel,
                importTable = { _, _, _ -> JobWorkerOutcome.Succeeded() },
            )
            outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
                .errorCode shouldBe BundleImportPipeline.ERROR_BUNDLE_INVALID
        } finally {
            cleanupZip(zip)
        }
    }
})
