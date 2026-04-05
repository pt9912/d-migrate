package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import dev.dmigrate.format.report.TransformationReportWriter
import dev.dmigrate.format.yaml.YamlSchemaCodec
import java.nio.file.Path
import kotlin.io.path.writeText

class SchemaCommand : CliktCommand(name = "schema") {
    override fun help(context: Context) = "Schema management commands"

    init {
        subcommands(SchemaValidateCommand(), SchemaGenerateCommand())
    }

    override fun run() = Unit
}

class SchemaValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate a schema definition"

    val source by option("--source", help = "Path to schema file (YAML)")
        .path(mustExist = true, canBeDir = false)
        .required()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: dev.dmigrate.cli.CliContext()
        val formatter = OutputFormatter(ctx)

        val schema = try {
            YamlSchemaCodec().read(source)
        } catch (e: Exception) {
            formatter.printError("Failed to parse schema file: ${e.message}", source.toString())
            throw ProgramResult(7)
        }

        val result = SchemaValidator().validate(schema)
        formatter.printValidationResult(result, schema, source.toString())

        if (!result.isValid) {
            throw ProgramResult(3)
        }
    }
}

class SchemaGenerateCommand : CliktCommand(name = "generate") {
    override fun help(context: Context) = "Generate database-specific DDL from a schema definition"

    val source by option("--source", help = "Path to schema file (YAML)")
        .path(mustExist = true, canBeDir = false)
        .required()
    val target by option("--target", help = "Target database dialect (postgresql, mysql, sqlite)")
        .required()
    val output by option("--output", help = "Output file path (default: stdout)")
        .path()
    val report by option("--report", help = "Report file path (default: <output>.report.yaml)")
        .path()
    val generateRollback by option("--generate-rollback", help = "Generate rollback DDL")
        .flag()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: dev.dmigrate.cli.CliContext()
        val formatter = OutputFormatter(ctx)

        // Parse dialect
        val dialect = try {
            DatabaseDialect.fromString(target)
        } catch (e: IllegalArgumentException) {
            formatter.printError(e.message ?: "Unknown dialect", source.toString())
            throw ProgramResult(2)
        }

        // Read schema
        val schema = try {
            YamlSchemaCodec().read(source)
        } catch (e: Exception) {
            formatter.printError("Failed to parse schema file: ${e.message}", source.toString())
            throw ProgramResult(7)
        }

        // Validate
        val validationResult = SchemaValidator().validate(schema)
        if (!validationResult.isValid) {
            formatter.printValidationResult(validationResult, schema, source.toString())
            throw ProgramResult(3)
        }

        // Generate DDL
        val generator = getGenerator(dialect)
        val result = generator.generate(schema)

        // Print warnings/action_required to stderr
        for (note in result.notes) {
            when (note.type) {
                NoteType.WARNING -> System.err.println("  ⚠ Warning [${note.code}]: ${note.message}")
                NoteType.ACTION_REQUIRED -> {
                    System.err.println("  ⚠ Action required [${note.code}]: ${note.message}")
                    if (note.hint != null) System.err.println("    → Hint: ${note.hint}")
                }
                NoteType.INFO -> if (ctx.verbose) System.err.println("  ℹ Info [${note.code}]: ${note.message}")
            }
        }
        for (skip in result.skippedObjects) {
            System.err.println("  ⚠ Skipped ${skip.type} '${skip.name}': ${skip.reason}")
        }

        // Write DDL output
        val ddl = result.render()
        if (ctx.outputFormat == "json") {
            println(formatJsonOutput(result, schema, dialect.name.lowercase()))
        } else if (output != null) {
            output!!.writeText(ddl + "\n")
            if (!ctx.quiet) System.err.println("DDL written to $output")

            // Generate rollback if requested
            if (generateRollback) {
                val rollbackResult = generator.generateRollback(schema)
                val rbPath = rollbackPath(output!!)
                rbPath.writeText(rollbackResult.render() + "\n")
                if (!ctx.quiet) System.err.println("Rollback DDL written to $rbPath")
            }

            // Write transformation report (sidecar or explicit --report)
            writeReport(result, schema, dialect.name.lowercase(), output!!)
        } else {
            println(ddl)
            if (generateRollback) {
                println("\n-- ═══════════════════════════════════════")
                println("-- ROLLBACK")
                println("-- ═══════════════════════════════════════\n")
                println(generator.generateRollback(schema).render())
            }

            // Write report if explicitly requested (even without --output)
            if (report != null) {
                writeReport(result, schema, dialect.name.lowercase(), report!!)
            }
        }
    }

    private fun getGenerator(dialect: DatabaseDialect): DdlGenerator = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgresDdlGenerator()
        DatabaseDialect.MYSQL -> MysqlDdlGenerator()
        DatabaseDialect.SQLITE -> SqliteDdlGenerator()
    }

    private fun writeReport(result: DdlResult, schema: dev.dmigrate.core.model.SchemaDefinition, dialect: String, outputPath: Path) {
        val reportPath = report ?: sidecarPath(outputPath, ".report.yaml")
        TransformationReportWriter().write(reportPath, result, schema, dialect, source)
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: dev.dmigrate.cli.CliContext()
        if (!ctx.quiet) System.err.println("Report written to $reportPath")
    }

    private fun formatJsonOutput(result: DdlResult, schema: dev.dmigrate.core.model.SchemaDefinition, dialect: String): String {
        val esc = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") }
        val notes = result.notes.joinToString(",\n") { n ->
            """    {"type": "${n.type.name.lowercase()}", "code": "${n.code}", "object": "${esc(n.objectName)}", "message": "${esc(n.message)}"}"""
        }
        val skipped = result.skippedObjects.joinToString(",\n") { s ->
            """    {"type": "${s.type}", "name": "${esc(s.name)}", "reason": "${esc(s.reason)}"}"""
        }
        return buildString {
            appendLine("{")
            appendLine("""  "command": "schema.generate",""")
            appendLine("""  "status": "completed",""")
            appendLine("""  "exit_code": 0,""")
            appendLine("""  "target": "$dialect",""")
            appendLine("""  "schema": {"name": "${esc(schema.name)}", "version": "${esc(schema.version)}"},""")
            appendLine("""  "ddl": "${esc(result.render())}",""")
            appendLine("""  "warnings": ${result.notes.count { it.type == NoteType.WARNING }},""")
            appendLine("""  "action_required": ${result.notes.count { it.type == NoteType.ACTION_REQUIRED }},""")
            if (notes.isEmpty()) appendLine("""  "notes": [],""") else {
                appendLine("""  "notes": ["""); appendLine(notes); appendLine("  ],")
            }
            if (skipped.isEmpty()) appendLine("""  "skipped_objects": []""") else {
                appendLine("""  "skipped_objects": ["""); appendLine(skipped); appendLine("  ]")
            }
            append("}")
        }
    }

    private fun sidecarPath(outputPath: Path, suffix: String): Path {
        val fileName = outputPath.fileName.toString()
        val dotIndex = fileName.lastIndexOf('.')
        val sidecarName = if (dotIndex > 0) {
            "${fileName.substring(0, dotIndex)}$suffix"
        } else {
            "$fileName$suffix"
        }
        return outputPath.parent?.resolve(sidecarName) ?: Path.of(sidecarName)
    }

    private fun rollbackPath(outputPath: Path): Path {
        val fileName = outputPath.fileName.toString()
        val dotIndex = fileName.lastIndexOf('.')
        val rollbackName = if (dotIndex > 0) {
            "${fileName.substring(0, dotIndex)}.rollback${fileName.substring(dotIndex)}"
        } else {
            "$fileName.rollback"
        }
        return outputPath.parent?.resolve(rollbackName) ?: Path.of(rollbackName)
    }
}
