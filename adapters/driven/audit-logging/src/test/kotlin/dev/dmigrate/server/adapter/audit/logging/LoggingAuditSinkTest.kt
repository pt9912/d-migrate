package dev.dmigrate.server.adapter.audit.logging

import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.slf4j.Logger
import java.time.Instant

class LoggingAuditSinkTest : FunSpec({

    fun newSink(infoEnabled: Boolean = true): Pair<LoggingAuditSink, MutableList<String>> {
        val captured = mutableListOf<String>()
        val logger = mockk<Logger>(relaxed = true)
        every { logger.isInfoEnabled } returns infoEnabled
        every { logger.info(any<String>()) } answers { captured.add(firstArg()) }
        return LoggingAuditSink(logger) to captured
    }

    val baseEvent = AuditEvent(
        requestId = "req-1",
        outcome = AuditOutcome.SUCCESS,
        startedAt = Instant.parse("2026-04-25T10:00:00Z"),
        toolName = "data.export",
        tenantId = TenantId("acme"),
        principalId = PrincipalId("alice"),
        durationMs = 42,
    )

    test("emits single JSON line per event") {
        val (sink, lines) = newSink()
        sink.emit(baseEvent)
        lines.size shouldBe 1
        val line = lines.single()
        line.startsWith("{") shouldBe true
        line.endsWith("}") shouldBe true
        line shouldNotContain "\n"
    }

    test("includes required fields with correct shapes") {
        val (sink, lines) = newSink()
        sink.emit(baseEvent)
        val line = lines.single()
        line shouldContain "\"requestId\":\"req-1\""
        line shouldContain "\"outcome\":\"SUCCESS\""
        line shouldContain "\"startedAt\":\"2026-04-25T10:00:00Z\""
        line shouldContain "\"toolName\":\"data.export\""
        line shouldContain "\"tenantId\":\"acme\""
        line shouldContain "\"principalId\":\"alice\""
        line shouldContain "\"durationMs\":42"
    }

    test("omits null fields entirely") {
        val (sink, lines) = newSink()
        sink.emit(
            AuditEvent(
                requestId = "req-2",
                outcome = AuditOutcome.SUCCESS,
                startedAt = Instant.parse("2026-04-25T10:00:00Z"),
            ),
        )
        val line = lines.single()
        line shouldNotContain "toolName"
        line shouldNotContain "tenantId"
        line shouldNotContain "principalId"
        line shouldNotContain "errorCode"
        line shouldNotContain "payloadFingerprint"
        line shouldNotContain "resourceRefs"
        line shouldNotContain "durationMs"
        line shouldNotContain "null"
    }

    test("includes errorCode on failure") {
        val (sink, lines) = newSink()
        sink.emit(baseEvent.copy(outcome = AuditOutcome.FAILURE, errorCode = ToolErrorCode.AUTH_REQUIRED))
        val line = lines.single()
        line shouldContain "\"outcome\":\"FAILURE\""
        line shouldContain "\"errorCode\":\"AUTH_REQUIRED\""
    }

    test("emits resourceRefs as a JSON array, omits empty list") {
        val (sink, lines) = newSink()
        sink.emit(baseEvent.copy(resourceRefs = listOf("uri-a", "uri-b")))
        lines.single() shouldContain "\"resourceRefs\":[\"uri-a\",\"uri-b\"]"

        sink.emit(baseEvent.copy(resourceRefs = emptyList()))
        lines.last() shouldNotContain "resourceRefs"
    }

    test("escapes quote, backslash and control characters in string values") {
        val (sink, lines) = newSink()
        sink.emit(baseEvent.copy(toolName = "weird\"name"))
        lines.last() shouldContain "\"toolName\":\"weird\\\"name\""

        sink.emit(baseEvent.copy(toolName = "with\\backslash"))
        lines.last() shouldContain "\"toolName\":\"with\\\\backslash\""

        sink.emit(baseEvent.copy(toolName = "ctrl\nchar"))
        lines.last() shouldContain "\"toolName\":\"ctrl\\u000achar\""
    }

    test("skips serialization when the audit logger is disabled") {
        val (sink, lines) = newSink(infoEnabled = false)
        sink.emit(baseEvent)
        lines.size shouldBe 0
    }

    test("includes payloadFingerprint when present") {
        val (sink, lines) = newSink()
        sink.emit(baseEvent.copy(payloadFingerprint = "a".repeat(64)))
        lines.single() shouldContain "\"payloadFingerprint\":\"${"a".repeat(64)}\""
    }
})
