package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

abstract class AuditSinkContractTests(factory: () -> InMemoryAuditSink) : FunSpec({

    test("emit records the event in order") {
        val sink = factory()
        val first = AuditEvent(
            requestId = "req_1",
            outcome = AuditOutcome.SUCCESS,
            startedAt = Fixtures.NOW,
            toolName = "data_export_start",
        )
        val second = AuditEvent(
            requestId = "req_2",
            outcome = AuditOutcome.FAILURE,
            startedAt = Fixtures.NOW.plusSeconds(1),
            errorCode = ToolErrorCode.VALIDATION_ERROR,
        )
        sink.emit(first)
        sink.emit(second)
        sink.recorded() shouldBe listOf(first, second)
    }

    test("clear removes recorded events") {
        val sink = factory()
        sink.emit(
            AuditEvent(
                requestId = "req_1",
                outcome = AuditOutcome.SUCCESS,
                startedAt = Fixtures.NOW,
            ),
        )
        sink.clear()
        sink.recorded() shouldBe emptyList()
    }
})
