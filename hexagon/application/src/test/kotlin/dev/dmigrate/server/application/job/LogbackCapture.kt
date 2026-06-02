package dev.dmigrate.server.application.job

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory

/**
 * LF-012 / LN-011 / LN-017 / LN-027) test helper: kapselt einen Logback-
 * `ListAppender` fuer die Lebensdauer eines `during {}`-Blocks und
 * gibt die gefangenen Events zurueck. Die slf4j-API liefert in dieser
 * Test-JVM einen Logback-`LoggerContext` (logback-classic ist
 * subprojects-weiter testRuntimeOnly), daher ist der Cast auf
 * `LoggerContext` sicher.
 *
 * Gleiches Pattern wie das bestehende
 * `adapters/driving/cli/.../LogbackCapture` aus AP 6.24 LF-017 / LF-024 / LN-030 / LN-031(C); wir
 * duplizieren hier bewusst, weil ein zentraler test-fixtures-Pfad fuer
 * Logging-Helpers bisher nicht etabliert ist.
 */
internal object LogbackCapture {

    fun <T> during(block: () -> T): CaptureResult<T> {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = context.getLogger(ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            this.context = context
            start()
        }
        rootLogger.addAppender(appender)
        return try {
            val value = block()
            CaptureResult(value, appender.list.toList())
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    data class CaptureResult<T>(
        val value: T,
        val events: List<ILoggingEvent>,
    )
}
