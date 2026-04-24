package dev.dmigrate.cli.output

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.i18n.UnicodeNormalizer
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult

class OutputFormatter(
    private val context: CliContext,
    private val messages: MessageResolver = MessageResolver(context.locale),
) {

    /** Normalizes display text using the configured Unicode normalization mode. */
    private fun norm(text: String): String =
        UnicodeNormalizer.normalize(text, context.normalization)

    fun printValidationResult(result: ValidationResult, schema: SchemaDefinition, source: String) {
        if (source.isEmpty()) Unit
        when (context.outputFormat) {
            "json" -> printJson(result, schema)
            "yaml" -> printYaml(result, schema)
            else -> printPlain(result, schema)
        }
    }

    fun printError(message: String, source: String) {
        when (context.outputFormat) {
            // Structured outputs stay English — no locale-dependent payloads
            "json" -> System.err.println("""{"error": "${escapeJson(message)}", "file": "${escapeJson(source)}"}""")
            "yaml" -> System.err.println("error: \"${escapeYaml(message)}\"\nfile: \"${escapeYaml(source)}\"")
            else -> System.err.println(
                messages.text("cli.error.plain_format", message) + "\n" +
                    messages.text("cli.error.plain_file", source)
            )
        }
    }

    private fun printPlain(result: ValidationResult, schema: SchemaDefinition) {
        if (!context.quiet) {
            println(messages.text("cli.validation.header", norm(schema.name), schema.version))
            println()
            println("  ${messages.text("cli.validation.tables", schema.tables.size)}")
            val columnCount = schema.tables.values.sumOf { it.columns.size }
            println("  ${messages.text("cli.validation.columns", columnCount)}")
            val indexCount = schema.tables.values.sumOf { it.indices.size }
            println("  ${messages.text("cli.validation.indices", indexCount)}")
            val constraintCount = schema.tables.values.sumOf { it.constraints.size }
            println("  ${messages.text("cli.validation.constraints", constraintCount)}")
            println()
            println(messages.text("cli.validation.results_header"))
        }

        if (result.isValid && result.warnings.isEmpty()) {
            if (!context.quiet) println("  ${green("✓")} ${messages.text("cli.validation.passed_marker")}")
        }

        for (warning in result.warnings) {
            System.err.println("  ${yellow("⚠")} ${messages.text("cli.validation.warning_line", warning.code, warning.message)}")
            if (!context.quiet) System.err.println("    ${messages.text("cli.validation.path_arrow", warning.objectPath)}")
        }

        for (error in result.errors) {
            System.err.println("  ${red("✗")} ${messages.text("cli.validation.error_line", error.code, error.message)}")
            if (!context.quiet) System.err.println("    ${messages.text("cli.validation.path_arrow", error.objectPath)}")
        }

        if (!context.quiet) {
            println()
            if (result.isValid) {
                println(messages.text("cli.validation.passed_summary", result.warnings.size))
            } else {
                println(messages.text("cli.validation.failed_summary", result.errors.size, result.warnings.size))
            }
        }
    }

    // JSON and YAML outputs remain English — structural contract
    private fun printJson(result: ValidationResult, schema: SchemaDefinition) {
        val results = mutableListOf<String>()
        for (w in result.warnings) {
            results += renderValidationEntry(
                level = "warning",
                code = w.code,
                objectPath = w.objectPath,
                message = w.message,
            )
        }
        for (e in result.errors) {
            results += renderValidationEntry(
                level = "error",
                code = e.code,
                objectPath = e.objectPath,
                message = e.message,
            )
        }
        println(buildString {
            appendLine("{")
            appendLine("""  "command": "schema.validate",""")
            appendLine("""  "status": "${if (result.isValid) "passed" else "failed"}",""")
            appendLine("""  "exit_code": ${if (result.isValid) 0 else 3},""")
            appendLine("""  "schema": {"name": "${escapeJson(schema.name)}", "version": "${escapeJson(schema.version)}"},""")
            appendLine("""  "errors": ${result.errors.size},""")
            appendLine("""  "warnings": ${result.warnings.size},""")
            if (results.isEmpty()) {
                appendLine("""  "results": []""")
            } else {
                appendLine("""  "results": [""")
                appendLine(results.joinToString(",\n"))
                appendLine("  ]")
            }
            append("}")
        })
    }

    private fun renderValidationEntry(
        level: String,
        code: String,
        objectPath: String,
        message: String,
    ): String = buildString {
        append("""    {"level": "$level", """)
        append(""""code": "$code", """)
        append(""""object": "${escapeJson(objectPath)}", """)
        append(""""message": "${escapeJson(message)}"}""")
    }

    private fun printYaml(result: ValidationResult, schema: SchemaDefinition) {
        println("command: schema.validate")
        println("status: ${if (result.isValid) "passed" else "failed"}")
        println("exit_code: ${if (result.isValid) 0 else 3}")
        println("schema:")
        println("  name: \"${escapeYaml(schema.name)}\"")
        println("  version: \"${escapeYaml(schema.version)}\"")
        println("errors: ${result.errors.size}")
        println("warnings: ${result.warnings.size}")
        if (result.warnings.isEmpty() && result.errors.isEmpty()) {
            println("results: []")
        } else {
            println("results:")
            for (w in result.warnings) {
                println("  - level: warning")
                println("    code: ${w.code}")
                println("    object: \"${escapeYaml(w.objectPath)}\"")
                println("    message: \"${escapeYaml(w.message)}\"")
            }
            for (e in result.errors) {
                println("  - level: error")
                println("    code: ${e.code}")
                println("    object: \"${escapeYaml(e.objectPath)}\"")
                println("    message: \"${escapeYaml(e.message)}\"")
            }
        }
    }

    private fun green(text: String) = if (useColor()) "\u001b[32m$text\u001b[0m" else text
    private fun yellow(text: String) = if (useColor()) "\u001b[33m$text\u001b[0m" else text
    private fun red(text: String) = if (useColor()) "\u001b[31m$text\u001b[0m" else text

    private fun useColor(): Boolean = !context.noColor && System.console() != null

    private fun escapeJson(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun escapeYaml(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}
