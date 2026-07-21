package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.text.icu.IcuUnicodeTextService
import java.nio.file.Path

internal data class SchemaValidateOptions(
    /** Datei-Pfad, oder `-` für stdin (spec/cli-spec.md 10.3). */
    val source: String,
    val cliContext: CliContext,
)

internal object SchemaValidateWiring {

    fun execute(options: SchemaValidateOptions): Int {
        val formatter = OutputFormatter(options.cliContext, IcuUnicodeTextService())
        val isStdin = options.source == "-"
        val label = if (isStdin) "<stdin>" else options.source
        val schema = try {
            if (isStdin) {
                // Kein Datei-Suffix bei stdin → Format aus dem Inhalt sniffen, dann parsen.
                val bytes = System.`in`.readBytes()
                SchemaFileResolver.codecForFormat(sniffFormat(bytes)).read(bytes.inputStream())
            } else {
                val path = Path.of(options.source)
                SchemaFileResolver.codecForPath(path).read(path)
            }
        } catch (e: Exception) {
            formatter.printError("Failed to parse schema file: ${e.message}", label)
            return 7
        }

        val result = SchemaValidator().validate(schema)
        formatter.printValidationResult(result, schema, label)
        return if (result.isValid) 0 else 3
    }

    /**
     * Format-Heuristik für stdin (ohne Dateiendung): erstes Nicht-Whitespace-Zeichen `{`/`[`
     * → JSON, sonst YAML. Das neutrale Schema ist ein Objekt; JSON beginnt also mit `{`.
     */
    private fun sniffFormat(bytes: ByteArray): String {
        val firstNonWs = bytes.asSequence()
            .map { (it.toInt() and 0xFF).toChar() }
            .firstOrNull { !it.isWhitespace() }
        return if (firstNonWs == '{' || firstNonWs == '[') "json" else "yaml"
    }
}
