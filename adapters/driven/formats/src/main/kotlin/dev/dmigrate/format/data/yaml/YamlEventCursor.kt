package dev.dmigrate.format.data.yaml

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.format.data.EncodingDetector
import dev.dmigrate.format.data.json.CharsetTranscodingInputStream
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.events.DocumentEndEvent
import org.snakeyaml.engine.v2.events.DocumentStartEvent
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.MappingEndEvent
import org.snakeyaml.engine.v2.events.MappingStartEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.events.SequenceEndEvent
import org.snakeyaml.engine.v2.events.SequenceStartEvent
import org.snakeyaml.engine.v2.events.StreamEndEvent
import org.snakeyaml.engine.v2.events.StreamStartEvent
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal class YamlEventCursor(
    rawInput: InputStream,
    private val table: String,
    explicitEncoding: Charset?,
) : AutoCloseable {

    private val resolvedInput: InputStream
    private val events: Iterator<Event>

    init {
        val (charset, stream) = resolveEncoding(rawInput, explicitEncoding)
        resolvedInput = stream
        val utf8Stream = if (charset == StandardCharsets.UTF_8) {
            resolvedInput
        } else {
            CharsetTranscodingInputStream(resolvedInput, charset)
        }

        events = try {
            val parse = Parse(
                LoadSettings.builder()
                    .setCodePointLimit(Int.MAX_VALUE)
                    .build()
            )
            parse.parseInputStream(utf8Stream).iterator()
        } catch (e: YamlEngineException) {
            throw invalidYaml("invalid YAML input", e)
        }
    }

    fun expectTopLevelSequence() {
        val streamStart = nextEvent("reading YAML stream start")
        if (streamStart !is StreamStartEvent) {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML stream, got ${streamStart.eventId}",
            )
        }
        val docStart = nextEvent("reading YAML document start")
        if (docStart !is DocumentStartEvent) {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML document, got ${docStart.eventId}",
            )
        }
        val seqStart = nextEvent("reading YAML top-level sequence")
        if (seqStart !is SequenceStartEvent) {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML sequence of mappings, got ${seqStart.eventId}",
            )
        }
    }

    fun readMappingContent(): LinkedHashMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            when (val event = nextEvent("reading YAML mapping entry")) {
                is MappingEndEvent -> return map
                is ScalarEvent -> map[event.value] = readValue()
                else -> throw ImportSchemaMismatchException(
                    "Table '$table': expected scalar key in YAML mapping, got ${event.eventId}",
                )
            }
        }
    }

    fun validateTailAfterSequence() {
        val documentEnd = nextEvent("reading YAML document end after records sequence")
        if (documentEnd !is DocumentEndEvent) {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML document end after records sequence, got ${documentEnd.eventId}",
            )
        }
        val streamEnd = nextEvent("reading YAML stream end after records sequence")
        if (streamEnd !is StreamEndEvent) {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML stream end after records sequence, got ${streamEnd.eventId}",
            )
        }
        if (hasMoreEvents()) {
            val trailing = nextEvent("reading trailing YAML content")
            throw ImportSchemaMismatchException(
                "Table '$table': unexpected trailing YAML content after records sequence: ${trailing.eventId}",
            )
        }
    }

    fun nextEvent(context: String): Event = try {
        events.next()
    } catch (e: ImportSchemaMismatchException) {
        throw e
    } catch (e: NoSuchElementException) {
        throw invalidYaml("unexpected end of YAML input while $context", e)
    } catch (e: YamlEngineException) {
        throw invalidYaml("invalid YAML input while $context", e)
    }

    override fun close() {
        try {
            resolvedInput.close()
        } catch (_: Throwable) {
            // Idempotent cleanup
        }
    }

    private fun readValue(): Any? =
        when (val event = nextEvent("reading YAML value")) {
            is ScalarEvent -> resolveYamlScalar(event)
            is MappingStartEvent -> readNestedMapping()
            is SequenceStartEvent -> readNestedSequence()
            is AliasEvent -> throw ImportSchemaMismatchException(
                "Table '$table': YAML aliases are not supported for data import",
            )

            else -> throw ImportSchemaMismatchException(
                "Table '$table': unexpected YAML event ${event.eventId} in value position",
            )
        }

    private fun readNestedMapping(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            when (val event = nextEvent("reading nested YAML mapping")) {
                is MappingEndEvent -> return map
                is ScalarEvent -> map[event.value] = readValue()
                else -> throw ImportSchemaMismatchException(
                    "Table '$table': expected scalar key in YAML mapping, got ${event.eventId}",
                )
            }
        }
    }

    private fun readNestedSequence(): List<Any?> {
        val list = mutableListOf<Any?>()
        while (true) {
            when (val event = nextEvent("reading nested YAML sequence")) {
                is SequenceEndEvent -> return list
                is ScalarEvent -> list.add(resolveYamlScalar(event))
                is MappingStartEvent -> list.add(readNestedMapping())
                is SequenceStartEvent -> list.add(readNestedSequence())
                is AliasEvent -> throw ImportSchemaMismatchException(
                    "Table '$table': YAML aliases are not supported",
                )

                else -> throw ImportSchemaMismatchException(
                    "Table '$table': unexpected event ${event.eventId}",
                )
            }
        }
    }

    private fun hasMoreEvents(): Boolean = try {
        events.hasNext()
    } catch (e: YamlEngineException) {
        throw invalidYaml("invalid YAML input while checking for trailing content", e)
    }

    private fun invalidYaml(message: String, cause: Throwable): ImportSchemaMismatchException =
        ImportSchemaMismatchException("Table '$table': $message", cause)

    companion object {
        private fun resolveEncoding(
            raw: InputStream,
            explicit: Charset?,
        ): EncodingDetector.Detected =
            if (explicit == null) {
                EncodingDetector.detectOrFallback(raw)
            } else {
                EncodingDetector.Detected(explicit, EncodingDetector.wrapWithExplicit(raw, explicit))
            }
    }
}
