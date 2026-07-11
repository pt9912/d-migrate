package dev.dmigrate.cli.audit

import dev.dmigrate.cli.config.ResolvedAuditSettings
import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.ports.AuditSink
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CliAuditRecorderTest : FunSpec({

    class CapturingSink : AuditSink {
        val events = mutableListOf<AuditEvent>()
        override fun emit(event: AuditEvent) { events += event }
    }

    val clock = Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneOffset.UTC)

    fun recorder(sink: AuditSink) = DefaultCliAuditRecorder(sink, clock) { "req-fixed" }

    test("Exit 0 → ein SUCCESS-Event mit exitCode 0") {
        val sink = CapturingSink()
        val exit = recorder(sink).record("data.export", listOf("db:host")) { 0 }
        exit shouldBe 0
        sink.events shouldHaveSize 1
        val e = sink.events.single()
        e.outcome shouldBe AuditOutcome.SUCCESS
        e.exitCode shouldBe 0
        e.toolName shouldBe "data.export"
        e.requestId shouldBe "req-fixed"
    }

    test("Exit ≠0 → FAILURE-Event mit exitCode") {
        val sink = CapturingSink()
        val exit = recorder(sink).record("data.transfer", emptyList()) { 5 }
        exit shouldBe 5
        val e = sink.events.single()
        e.outcome shouldBe AuditOutcome.FAILURE
        e.exitCode shouldBe 5
    }

    test("resourceRefs werden per SecretScrubber gescrubbt") {
        val sink = CapturingSink()
        recorder(sink).record("schema.reverse", listOf("postgres://user:hunter2@host:5432/db")) { 0 }
        val refs = sink.events.single().resourceRefs.single()
        refs shouldNotContain "hunter2"
        refs shouldContain "host"
    }

    test("best-effort: Sink-Fehler crasht die Operation nicht") {
        val throwing = object : AuditSink {
            override fun emit(event: AuditEvent): Unit = throw java.io.IOException("disk full")
        }
        val exit = recorder(throwing).record("schema.migrate", emptyList()) { 0 }
        exit shouldBe 0 // Sink-Fehler geschluckt, Exit-Code unverändert
    }

    test("Block wirft → FAILURE-Event (exitCode null) + Exception weitergereicht") {
        val sink = CapturingSink()
        shouldThrow<IllegalStateException> {
            recorder(sink).record("schema.migrate", emptyList()) { throw IllegalStateException("boom") }
        }
        val e = sink.events.single()
        e.outcome shouldBe AuditOutcome.FAILURE
        e.exitCode.shouldBeNull()
    }

    test("NoOp: ruft block, emittiert nichts") {
        val sink = CapturingSink()
        var ran = false
        val exit = NoOpCliAuditRecorder.record("data.export", listOf("db:host")) { ran = true; 7 }
        exit shouldBe 7
        ran shouldBe true
        sink.events.shouldBeEmpty()
    }

    test("fromSettings: enabled → Default, opt-out → NoOp") {
        val file = Paths.get(".d-migrate/audit.log")
        CliAuditRecorders.fromSettings(ResolvedAuditSettings(enabled = false, file = file)) shouldBe NoOpCliAuditRecorder
        CliAuditRecorders.fromSettings(ResolvedAuditSettings(enabled = true, file = file))
            .shouldBeInstanceOf<DefaultCliAuditRecorder>()
    }

    test("E2E: cliAuditRecorder aus enabled-Config schreibt JSONL-Event in die Datei") {
        val dir = Files.createTempDirectory("audit-e2e")
        val auditFile = dir.resolve("out/audit.log")
        val config = dir.resolve(".d-migrate.yaml")
        Files.writeString(
            config,
            """
            logging:
              audit:
                enabled: true
                file: "$auditFile"
            """.trimIndent(),
        )

        val exit = cliAuditRecorder(config).record("data.export", listOf("postgres://u:pw@h/d")) { 3 }

        exit shouldBe 3
        val lines = Files.readAllLines(auditFile)
        lines shouldHaveSize 1
        lines.single() shouldContain "\"toolName\":\"data.export\""
        lines.single() shouldContain "\"exitCode\":3"
        lines.single() shouldContain "\"outcome\":\"FAILURE\""
        lines.single() shouldNotContain "pw"
    }

    test("cliAuditRecorder: unlesbare Config (best-effort) → NoOp, kein Wurf") {
        cliAuditRecorder(Paths.get("/nonexistent/x/.d-migrate.yaml")) shouldBe NoOpCliAuditRecorder
    }
})
