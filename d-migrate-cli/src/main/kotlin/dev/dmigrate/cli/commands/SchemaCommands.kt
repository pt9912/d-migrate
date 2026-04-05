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
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
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
        if (output != null) {
            output!!.writeText(ddl + "\n")
            if (!ctx.quiet) System.err.println("DDL written to $output")

            // Generate rollback if requested
            if (generateRollback) {
                val rollbackResult = generator.generateRollback(schema)
                val rollbackPath = rollbackPath(output!!)
                rollbackPath.writeText(rollbackResult.render() + "\n")
                if (!ctx.quiet) System.err.println("Rollback DDL written to $rollbackPath")
            }
        } else {
            println(ddl)
            if (generateRollback) {
                println("\n-- ═══════════════════════════════════════")
                println("-- ROLLBACK")
                println("-- ═══════════════════════════════════════\n")
                println(generator.generateRollback(schema).render())
            }
        }
    }

    private fun getGenerator(dialect: DatabaseDialect): DdlGenerator = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgresDdlGenerator()
        DatabaseDialect.MYSQL -> MysqlDdlGenerator()
        DatabaseDialect.SQLITE -> SqliteDdlGenerator()
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
