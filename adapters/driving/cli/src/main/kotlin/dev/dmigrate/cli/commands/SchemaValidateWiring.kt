package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.text.icu.IcuUnicodeTextService
import java.nio.file.Path

internal data class SchemaValidateOptions(
    val source: Path,
    val cliContext: CliContext,
)

internal object SchemaValidateWiring {

    fun execute(options: SchemaValidateOptions): Int {
        val formatter = OutputFormatter(options.cliContext, IcuUnicodeTextService())
        val schema = try {
            SchemaFileResolver.codecForPath(options.source).read(options.source)
        } catch (e: Exception) {
            formatter.printError("Failed to parse schema file: ${e.message}", options.source.toString())
            return 7
        }

        val result = SchemaValidator().validate(schema)
        formatter.printValidationResult(result, schema, options.source.toString())
        return if (result.isValid) 0 else 3
    }
}
