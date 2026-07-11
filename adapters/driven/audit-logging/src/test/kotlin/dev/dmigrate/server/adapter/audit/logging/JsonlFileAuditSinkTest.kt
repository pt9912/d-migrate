package dev.dmigrate.server.adapter.audit.logging

import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.core.audit.AuditOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.slf4j.Logger
import java.nio.file.Files
import java.time.Instant

class JsonlFileAuditSinkTest : FunSpec({

    fun event(requestId: String, outcome: AuditOutcome = AuditOutcome.SUCCESS, exitCode: Int? = null) = AuditEvent(
        requestId = requestId,
        outcome = outcome,
        startedAt = Instant.parse("2026-07-11T10:00:00Z"),
        toolName = "data.export",
        resourceRefs = listOf("db:postgres://u:p@host/db"),
        durationMs = 12,
        exitCode = exitCode,
    )

    test("legt das Parent-Verzeichnis an und schreibt eine JSONL-Zeile") {
        val file = Files.createTempDirectory("audit").resolve("nested/dir/audit.log")
        JsonlFileAuditSink(file).emit(event("req-1"))

        val lines = Files.readAllLines(file)
        lines shouldHaveSize 1
        val line = lines.single()
        line.startsWith("{") shouldBe true
        line.endsWith("}") shouldBe true
        line shouldContain "\"requestId\":\"req-1\""
        line shouldContain "\"outcome\":\"SUCCESS\""
        line shouldContain "\"toolName\":\"data.export\""
    }

    test("hängt aufeinanderfolgende Events als getrennte Zeilen an (append)") {
        val file = Files.createTempDirectory("audit").resolve("audit.log")
        val sink = JsonlFileAuditSink(file)
        sink.emit(event("req-1"))
        sink.emit(event("req-2", AuditOutcome.FAILURE, exitCode = 5))

        val lines = Files.readAllLines(file)
        lines shouldHaveSize 2
        lines[0] shouldContain "\"requestId\":\"req-1\""
        lines[0] shouldNotContain "exitCode"
        lines[1] shouldContain "\"requestId\":\"req-2\""
        lines[1] shouldContain "\"outcome\":\"FAILURE\""
        lines[1] shouldContain "\"exitCode\":5"
    }

    test("byte-identisch zu LoggingAuditSink für dasselbe Event") {
        val file = Files.createTempDirectory("audit").resolve("audit.log")
        val ev = event("req-1", AuditOutcome.FAILURE, exitCode = 4)
        JsonlFileAuditSink(file).emit(ev)

        val captured = mutableListOf<String>()
        val logger = mockk<Logger>(relaxed = true)
        every { logger.isInfoEnabled } returns true
        every { logger.info(any<String>()) } answers { captured.add(firstArg()) }
        LoggingAuditSink(logger).emit(ev)

        Files.readAllLines(file).single() shouldBe captured.single()
    }
})
