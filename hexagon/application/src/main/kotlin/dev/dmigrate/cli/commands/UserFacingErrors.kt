package dev.dmigrate.cli.commands

import dev.dmigrate.driver.connection.ConnectionSecretMasker

internal class UserFacingErrors(
    private val urlScrubber: (String) -> String = ConnectionSecretMasker::mask,
) {

    fun scrubMessage(message: String?): String = message?.let(urlScrubber) ?: "unknown error"

    fun scrubRef(raw: String): String {
        if (raw.startsWith(DB_PREFIX)) {
            val operand = raw.removePrefix(DB_PREFIX)
            return "$DB_PREFIX${urlScrubber(operand)}"
        }
        return urlScrubber(raw)
    }

    fun stderrSink(delegate: (String) -> Unit): (String) -> Unit = { line ->
        delegate(scrubMessage(line))
    }

    fun printError(delegate: (String, String) -> Unit): (String, String) -> Unit = { message, source ->
        delegate(scrubMessage(message), scrubRef(source))
    }

    private companion object {
        const val DB_PREFIX = "db:"
    }
}
