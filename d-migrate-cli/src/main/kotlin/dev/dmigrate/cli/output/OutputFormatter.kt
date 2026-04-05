package dev.dmigrate.cli.output

import dev.dmigrate.cli.CliContext
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult

class OutputFormatter(private val context: CliContext) {

    fun printValidationResult(result: ValidationResult, schema: SchemaDefinition, source: String) {
        when (context.outputFormat) {
            "json" -> printJson(result, schema)
            "yaml" -> printYaml(result, schema)
            else -> printPlain(result, schema, source)
        }
    }

    fun printError(message: String, source: String) {
        when (context.outputFormat) {
            "json" -> System.err.println("""{"error": "${escapeJson(message)}", "file": "${escapeJson(source)}"}""")
            "yaml" -> System.err.println("error: \"${escapeYaml(message)}\"\nfile: \"${escapeYaml(source)}\"")
            else -> System.err.println("[ERROR] $message\n  → File: $source")
        }
    }

    private fun printPlain(result: ValidationResult, schema: SchemaDefinition, source: String) {
        if (!context.quiet) {
            println("Validating schema '${schema.name}' v${schema.version}...")
            println()
            println("  Tables:      ${schema.tables.size} found")
            val columnCount = schema.tables.values.sumOf { it.columns.size }
            println("  Columns:     $columnCount found")
            val indexCount = schema.tables.values.sumOf { it.indices.size }
            println("  Indices:     $indexCount found")
            val constraintCount = schema.tables.values.sumOf { it.constraints.size }
            println("  Constraints: $constraintCount found")
            println()
            println("Results:")
        }

        if (result.isValid && result.warnings.isEmpty()) {
            if (!context.quiet) println("  ${green("✓")} Validation passed")
        }

        for (warning in result.warnings) {
            System.err.println("  ${yellow("⚠")} Warning [${warning.code}]: ${warning.message}")
            if (!context.quiet) System.err.println("    → ${warning.objectPath}")
        }

        for (error in result.errors) {
            System.err.println("  ${red("✗")} Error [${error.code}]: ${error.message}")
            if (!context.quiet) System.err.println("    → ${error.objectPath}")
        }

        if (!context.quiet) {
            println()
            if (result.isValid) {
                println("Validation passed: ${result.warnings.size} warning(s)")
            } else {
                println("Validation failed: ${result.errors.size} error(s), ${result.warnings.size} warning(s)")
            }
        }
    }

    private fun printJson(result: ValidationResult, schema: SchemaDefinition) {
        val results = mutableListOf<String>()
        for (w in result.warnings) {
            results += """    {"level": "warning", "code": "${w.code}", "object": "${escapeJson(w.objectPath)}", "message": "${escapeJson(w.message)}"}"""
        }
        for (e in result.errors) {
            results += """    {"level": "error", "code": "${e.code}", "object": "${escapeJson(e.objectPath)}", "message": "${escapeJson(e.message)}"}"""
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
