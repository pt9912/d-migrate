package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayBinding
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.PartitionMappingOverlayEntry

class SchemaReverseRunnerTest : FunSpec({

    val fakeSchema = SchemaDefinition(name = "__dmigrate_reverse__:sqlite:schema=main", version = "0.0.0-reverse")
    val fakeResult = SchemaReadResult(
        schema = fakeSchema,
        notes = listOf(
            SchemaReadNote(SchemaReadSeverity.WARNING, "R001", "t1", "test warning"),
            SchemaReadNote(SchemaReadSeverity.INFO, "R002", "t2", "info note"),
        ),
        skippedObjects = listOf(SkippedObject("TABLE", "vt", "Virtual table", code = "S100")),
    )
    val fakeConfig = ConnectionConfig(DatabaseDialect.SQLITE, "localhost", null, "test", null, null)
    val fakePool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): DatabaseConnection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }
    val fakeReader = object : SchemaReader {
        override fun read(pool: ConnectionPool, options: SchemaReadOptions) = fakeResult
    }
    val fakeDriver = object : DatabaseDriver {
        override val dialect = DatabaseDialect.SQLITE
        override fun ddlGenerator() = throw UnsupportedOperationException()
        override fun dataReader() = throw UnsupportedOperationException()
        override fun tableLister() = throw UnsupportedOperationException()
        override fun dataWriter() = throw UnsupportedOperationException()
        override fun urlBuilder() = throw UnsupportedOperationException()
        override fun schemaReader() = fakeReader
    }

    class Capture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
        fun joined() = lines.joinToString("\n")
    }

    fun buildRunner(
        stdout: Capture = Capture(),
        stderr: Capture = Capture(),
        errors: Capture = Capture(),
        sourceResolver: (String, Path?) -> String = { s, _ -> s },
        urlParser: (String) -> ConnectionConfig = { fakeConfig },
        poolFactory: (ConnectionConfig) -> ConnectionPool = { fakePool },
        driverLookup: (DatabaseDialect) -> DatabaseDriver = { fakeDriver },
        schemaWriter: (Path, SchemaDefinition, String?) -> Unit = { _, _, _ -> },
        reportWriter: (Path, SchemaReadReportInput) -> Unit = { _, _ -> },
        formatValidator: (Path, String?) -> Unit = { _, _ -> },
    ) = Triple(
        SchemaReverseRunner(
            sourceResolver = sourceResolver,
            urlParser = urlParser,
            poolFactory = poolFactory,
            driverLookup = driverLookup,
            schemaWriter = schemaWriter,
            reportWriter = reportWriter,
            sidecarPath = { p, s ->
                val n = p.fileName.toString()
                val d = n.lastIndexOf('.')
                val sc = if (d > 0) "${n.substring(0, d)}$s" else "$n$s"
                p.parent?.resolve(sc) ?: Path.of(sc)
            },
            formatValidator = formatValidator,
            urlScrubber = { url -> url.replace(Regex(":[^@/]+@"), ":***@") },
            printError = { msg, src -> errors.sink("Error [$src]: $msg") },
            stdout = stdout.sink,
            stderr = stderr.sink,
        ),
        stdout to stderr,
        errors,
    )

    fun request(
        source: String = "sqlite:///test.db",
        output: Path = Path.of("/tmp/reverse.yaml"),
        format: String = "yaml",
        report: Path? = null,
        outputFormat: String = "plain",
        quiet: Boolean = false,
        verbose: Boolean = false,
        includeAll: Boolean = false,
        schemaName: String? = null,
        schemaVersion: String? = null,
        migrationOverlays: List<MigrationOverlayDocument> = emptyList(),
    ) = SchemaReverseRequest(
        migrationOverlays = migrationOverlays,
        source = source, output = output, format = format, report = report,
        outputFormat = outputFormat, quiet = quiet, verbose = verbose, includeAll = includeAll,
        schemaName = schemaName, schemaVersion = schemaVersion,
    )

    // ── Success ─────────────────────────────────

    // ── Overlays werden geprueft, bevor sie wirken ───────────────

    fun partitionOverlay(fingerprint: String) = MigrationOverlayDocument(
        source = "overlays/names.json",
        overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.PARTITION_MAPPING,
            binding = MigrationOverlayBinding.Representation(fingerprint),
            dialect = "sqlite",
            entries = listOf(
                PartitionMappingOverlayEntry(
                    id = "a", table = "t", sourcePartition = "p_2024", targetPartition = "p1",
                ),
            ),
            createdAt = "2026-09-08T10:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash(),
    )

    test("a stale overlay stops the run instead of silently setting wrong names") {
        // Der Abdruck gehoert zu einem anderen Schema. Es anzuwenden ergaebe
        // ein Ergebnis, das aussieht wie ein gutes.
        val errors = Capture()
        val (runner, _, _) = buildRunner(errors = errors)
        runner.execute(request(migrationOverlays = listOf(partitionOverlay("0000")))) shouldBe 2
        errors.joined() shouldContain MigrationOverlayDiagnostics.STALE_SCHEMA_FINGERPRINT
    }

    test("a transition-bound overlay is refused — a reverse knows no schema pair") {
        val errors = Capture()
        val (runner, _, _) = buildRunner(errors = errors)
        val transition = MigrationOverlayDocument(
            source = "overlays/rename.json",
            overlay = MigrationOverlay(
                overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
                binding = MigrationOverlayBinding.Transition("a", "b"),
                dialect = "sqlite",
                entries = emptyList(),
                createdAt = "2026-09-08T10:00:00Z",
                createdByVersion = "d-migrate-test",
            ).withComputedHash(),
        )
        runner.execute(request(migrationOverlays = listOf(transition))) shouldBe 2
        errors.joined() shouldContain "OVERLAY_STALE"
    }

    test("a matching overlay lets the run through") {
        val matching = partitionOverlay(
            PartitionOverlayHint.representationFingerprint(fakeResult.schema, DatabaseDialect.SQLITE),
        )
        val (runner, _, _) = buildRunner()
        runner.execute(request(migrationOverlays = listOf(matching))) shouldBe 0
    }

    test("a hand-written overlay gets its hash from the refusal it triggers") {
        // Den Weg, den das Handbuch beschreibt, einmal ganz gehen: wer die
        // Datei selbst schreibt, kann die Pruefsumme nicht ausrechnen. Sie
        // muss also aus der Ablehnung hervorgehen, sonst ist das Overlay
        // nicht abzugeben.
        val fingerprint = PartitionOverlayHint.representationFingerprint(fakeResult.schema, DatabaseDialect.SQLITE)
        val unsigned = MigrationOverlayDocument(
            source = "overlays/handgeschrieben.json",
            overlay = partitionOverlay(fingerprint).overlay.copy(overlayHash = null),
        )

        val errors = Capture()
        val (runner, _, _) = buildRunner(errors = errors)
        runner.execute(request(migrationOverlays = listOf(unsigned))) shouldBe 2

        val message = errors.joined()
        val hash = Regex("'([0-9a-f]{64})'").find(message)?.groupValues?.get(1)
        withClue(message) { hash.shouldNotBeNull() }

        val (second, _, _) = buildRunner()
        second.execute(
            request(migrationOverlays = listOf(unsigned.copy(overlay = unsigned.overlay.copy(overlayHash = hash)))),
        ) shouldBe 0
    }

    test("successful reverse returns exit 0") {
        val (runner, _, _) = buildRunner()
        runner.execute(request()) shouldBe 0
    }

    test("success plain prints paths to stdout and notes to stderr") {
        val stdout = Capture(); val stderr = Capture()
        val (runner, _, _) = buildRunner(stdout = stdout, stderr = stderr)
        runner.execute(request()) shouldBe 0
        stdout.joined() shouldContain "Schema written to"
        stderr.joined() shouldContain "WARNING [R001]"
        stderr.joined() shouldContain "SKIPPED [S100]"
    }

    test("verbose shows INFO notes") {
        val stderr = Capture()
        val (runner, _, _) = buildRunner(stderr = stderr)
        runner.execute(request(verbose = true)) shouldBe 0
        stderr.joined() shouldContain "INFO [R002]"
    }

    test("non-verbose hides INFO notes") {
        val stderr = Capture()
        val (runner, _, _) = buildRunner(stderr = stderr)
        runner.execute(request(verbose = false)) shouldBe 0
        stderr.joined() shouldNotContain "INFO [R002]"
    }

    test("default sidecar is <basename>.report.yaml") {
        val stdout = Capture()
        val (runner, _, _) = buildRunner(stdout = stdout)
        runner.execute(request(output = Path.of("/tmp/schema.yaml"))) shouldBe 0
        stdout.joined() shouldContain "schema.report.yaml"
    }

    test("schema metadata overrides are applied before writing schema and report") {
        var writtenSchema: SchemaDefinition? = null
        var reportedSchema: SchemaDefinition? = null
        val (runner, _, _) = buildRunner(
            schemaWriter = { _, schema, _ -> writtenSchema = schema },
            reportWriter = { _, input -> reportedSchema = input.result.schema },
        )

        runner.execute(request(schemaName = "bess-ems", schemaVersion = "1.0.0")) shouldBe 0

        writtenSchema!!.name shouldBe "bess-ems"
        writtenSchema!!.version shouldBe "1.0.0"
        reportedSchema!!.name shouldBe "bess-ems"
        reportedSchema!!.version shouldBe "1.0.0"
    }

    // ── Output format json/yaml ─────────────────

    test("output-format json produces structured success document") {
        val stdout = Capture()
        val (runner, _, _) = buildRunner(stdout = stdout)
        runner.execute(request(outputFormat = "json")) shouldBe 0
        stdout.joined() shouldContain """"command": "schema.reverse""""
        stdout.joined() shouldContain """"status": "success""""
    }

    test("output-format json has no stderr notes") {
        val stderr = Capture()
        val (runner, _, _) = buildRunner(stderr = stderr)
        runner.execute(request(outputFormat = "json")) shouldBe 0
        stderr.lines.size shouldBe 0
    }

    test("output-format yaml produces structured success document") {
        val stdout = Capture()
        val (runner, _, _) = buildRunner(stdout = stdout)
        runner.execute(request(outputFormat = "yaml")) shouldBe 0
        stdout.joined() shouldContain "command: schema.reverse"
    }

    // ── Quiet ───────────────────────────────────

    test("quiet suppresses all non-error output") {
        val stdout = Capture(); val stderr = Capture()
        val (runner, _, _) = buildRunner(stdout = stdout, stderr = stderr)
        runner.execute(request(quiet = true)) shouldBe 0
        stdout.lines.size shouldBe 0
        stderr.lines.size shouldBe 0
    }

    test("quiet does not suppress errors") {
        val errors = Capture()
        val (runner, _, _) = buildRunner(
            errors = errors,
            sourceResolver = { _, _ -> throw RuntimeException("fail") },
        )
        runner.execute(request(quiet = true)) shouldBe 7
        errors.lines.size shouldBe 1
    }

    // ── Exit 2 ──────────────────────────────────

    test("format mismatch returns exit 2") {
        val (runner, _, _) = buildRunner(
            formatValidator = { _, _ -> throw IllegalArgumentException("mismatch") },
        )
        runner.execute(request()) shouldBe 2
    }

    test("output/report collision returns exit 2") {
        val same = Path.of("/tmp/same.yaml")
        val (runner, _, _) = buildRunner()
        runner.execute(request(output = same, report = same)) shouldBe 2
    }

    // ── Exit 7 ──────────────────────────────────

    test("source resolution failure returns exit 7") {
        val (runner, _, _) = buildRunner(
            sourceResolver = { _, _ -> throw RuntimeException("not found") },
        )
        runner.execute(request()) shouldBe 7
    }

    test("schema write failure returns exit 7") {
        val (runner, _, _) = buildRunner(
            schemaWriter = { _, _, _ -> throw RuntimeException("disk full") },
        )
        runner.execute(request()) shouldBe 7
    }

    // ── Exit 4 ──────────────────────────────────

    test("pool creation failure returns exit 4") {
        val (runner, _, _) = buildRunner(
            poolFactory = { throw RuntimeException("connection refused") },
        )
        runner.execute(request()) shouldBe 4
    }

    // ── URL scrubbing ───────────────────────────

    test("URL source is scrubbed in error output") {
        val errors = Capture()
        val (runner, _, _) = buildRunner(
            errors = errors,
            poolFactory = { throw RuntimeException("failed for postgresql://admin:secret@host/db") },
        )
        runner.execute(request(source = "postgresql://admin:secret@host/db")) shouldBe 4
        errors.joined() shouldNotContain "secret"
        errors.joined() shouldContain "***"
    }

    // ── Include flags ───────────────────────────

    test("include-all sets all include flags") {
        var captured: SchemaReadOptions? = null
        val capturingReader = object : SchemaReader {
            override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
                captured = options; return fakeResult
            }
        }
        val capturingDriver = object : DatabaseDriver {
            override val dialect = DatabaseDialect.SQLITE
            override fun ddlGenerator() = throw UnsupportedOperationException()
            override fun dataReader() = throw UnsupportedOperationException()
            override fun tableLister() = throw UnsupportedOperationException()
            override fun dataWriter() = throw UnsupportedOperationException()
            override fun urlBuilder() = throw UnsupportedOperationException()
            override fun schemaReader() = capturingReader
        }
        val (runner, _, _) = buildRunner(driverLookup = { capturingDriver })
        runner.execute(request(includeAll = true)) shouldBe 0
        captured!!.includeViews shouldBe true
        captured!!.includeProcedures shouldBe true
        captured!!.includeFunctions shouldBe true
        captured!!.includeTriggers shouldBe true
    }
})
