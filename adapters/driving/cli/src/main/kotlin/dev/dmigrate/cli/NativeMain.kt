package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import dev.dmigrate.format.SchemaFileResolver
import java.nio.file.Path

/**
 * **GraalVM-Native-Image Core-Entrypoint** (1.0.0-Stable-Gate,
 * `docs/planning/in-progress/graalvm-native-image-distribution.md`, Phase B).
 *
 * Bewusst **reduziert**: nur die native-image-fähigen, DB-freien Kern-Kommandos — **ohne** die
 * eager-`IcuUnicodeTextService`-Verdrahtung der vollen [DMigrate]-Wurzel und **ohne** die
 * Schwergewichts-Pfade (Parquet/Hadoop, S3, Tool-Export). Der volle CLI ([main]) bleibt der
 * JVM-Fat-JAR-Pfad. Die native-image-Reachability-Analyse beschneidet damit ICU/Parquet/AWS von
 * selbst — dieser `main` referenziert sie nie.
 *
 * Kommandos: `schema validate` (Schema-gegen-Vertrag-Prüfung) und `schema generate` (DDL-Rendering
 * für ein Ziel-Dialekt). Beide sind **datei-basiert/DB-frei**: `generate` konstruiert den reinen
 * [DdlGenerator] direkt (kein [dev.dmigrate.driver.DatabaseDriver]/JDBC-Registry → das Native-Binary
 * bleibt JDBC- und sqlite-JNI-frei). Der Native-Build ist lokal + in CI (`native-image.yml`)
 * verifiziert.
 */
private class NativeRoot : CliktCommand(name = "d-migrate") {
    override fun help(context: Context) = "d-migrate (native core subset)"
    override fun run() = Unit
}

private class NativeSchemaGroup : CliktCommand(name = "schema") {
    override fun help(context: Context) = "Schema management commands (native core subset)"
    override fun run() = Unit
}

private class NativeSchemaValidate : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate a schema file against the neutral-model contract"

    private val file by argument(name = "FILE", help = "Schema file (YAML/JSON)")

    override fun run() {
        val path = Path.of(file)
        val schema = SchemaFileResolver.codecForPath(path).read(path)
        val result = SchemaValidator().validate(schema)
        echo("tables=${schema.tables.size} valid=${result.isValid} errors=${result.errors.size}")
        result.errors.forEach { echo("  [${it.code}] ${it.objectPath}: ${it.message}", err = true) }
        if (!result.isValid) throw ProgramResult(3)
    }
}

private class NativeSchemaGenerate : CliktCommand(name = "generate") {
    override fun help(context: Context) = "Generate DDL for a target dialect from a schema file"

    private val file by argument(name = "FILE", help = "Schema file (YAML/JSON)")
    private val target by option("--target", "-t", help = "Target dialect: postgresql, mysql, sqlite").required()

    override fun run() {
        val path = Path.of(file)
        val schema = SchemaFileResolver.codecForPath(path).read(path)
        val dialect = try {
            DatabaseDialect.fromString(target)
        } catch (e: IllegalArgumentException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(2)
        }
        // DB-frei: nur der reine DDL-Generator, kein DatabaseDriver/JDBC → Native-Binary bleibt schlank.
        val generator: DdlGenerator = when (dialect) {
            DatabaseDialect.POSTGRESQL -> PostgresDdlGenerator()
            DatabaseDialect.MYSQL -> MysqlDdlGenerator()
            DatabaseDialect.SQLITE -> SqliteDdlGenerator()
        }
        echo(generator.generate(schema).render())
    }
}

/** Native-image-Einstieg (`imageName = d-migrate`, `mainClass = dev.dmigrate.cli.NativeMainKt`). */
fun main(args: Array<String>) {
    NativeRoot().subcommands(
        NativeSchemaGroup().subcommands(NativeSchemaValidate(), NativeSchemaGenerate()),
    ).main(args)
}
