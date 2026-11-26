package dev.dmigrate.mcp.protocol

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.core.error.ToolErrorCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory

/** Ereignisse des [ToolFailureLog]-Loggers waehrend [block], bei dessen Standardstufe WARN. */
private fun captured(block: () -> Unit): List<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(ToolFailureLog::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    val previous = logger.level
    logger.level = Level.WARN
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
        logger.level = previous
        appender.stop()
    }
    return appender.list.toList()
}

class ToolFailureLogTest : FunSpec({

    test("unerwartete Ausnahme steht auf ERROR mit Tool, Request-ID, Ursachenkette und ohne Zugangsdaten") {
        val failure = IllegalStateException(
            "store read failed",
            RuntimeException("connect jdbc:postgresql://app:s3cret@db:5432/state?password=t0psecret failed"),
        )

        val events = captured {
            ToolFailureLog.record(failure, ToolErrorCode.INTERNAL_AGENT_ERROR, "job_list", "req-1")
        }

        val event = events.single()
        event.level shouldBe Level.ERROR
        // Die Ausnahme darf nicht als Throwable mitgehen: logback schriebe ihre Meldungen ungefiltert.
        event.throwableProxy shouldBe null
        val line = event.formattedMessage
        line shouldContain "tool 'job_list' (req req-1) failed with INTERNAL_AGENT_ERROR"
        line shouldContain "java.lang.IllegalStateException: store read failed"
        line shouldContain "Caused by: java.lang.RuntimeException"
        line shouldContain "\tat dev.dmigrate.mcp.protocol.ToolFailureLogTest"
        line shouldContain "app:***@db"
        line shouldNotContain "s3cret"
        line shouldNotContain "t0psecret"
    }

    test("InternalAgentErrorException ist ein interner Fehler und steht auf ERROR") {
        val events = captured {
            ToolFailureLog.record(
                InternalAgentErrorException(),
                ToolErrorCode.INTERNAL_AGENT_ERROR,
                "schema_compare",
                "req-2",
            )
        }

        events.single().level shouldBe Level.ERROR
    }

    test("erwartete Fehler bleiben bei der Standardstufe still") {
        val events = captured {
            ToolFailureLog.record(
                PolicyDeniedException(policyName = "p", reason = "r"),
                ToolErrorCode.POLICY_DENIED,
                "schema_reverse_start",
                "req-3",
            )
        }

        events.shouldBeEmpty()
    }
})
