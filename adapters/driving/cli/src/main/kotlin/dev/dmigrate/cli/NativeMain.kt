package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.format.SchemaFileResolver
import java.nio.file.Path

/**
 * **GraalVM-Native-Image Core-Entrypoint** (1.0.0-Stable-Gate,
 * `docs/planning/next/graalvm-native-image-distribution.md`, Phase B).
 *
 * Bewusst **reduziert**: nur die native-image-fähigen, DB-freien Kern-Kommandos — **ohne** die
 * eager-`IcuUnicodeTextService`-Verdrahtung der vollen [DMigrate]-Wurzel und **ohne** die
 * Schwergewichts-Pfade (Parquet/Hadoop, S3, Tool-Export). Der volle CLI ([main]) bleibt der
 * JVM-Fat-JAR-Pfad. Die native-image-Reachability-Analyse beschneidet damit ICU/Parquet/AWS von
 * selbst — dieser `main` referenziert sie nie.
 *
 * Erstes Subkommando: `schema validate` (Schema-gegen-Vertrag-Prüfung, rein datei-basiert). Weitere
 * (`schema generate` mit Treiber-Registry) folgen als eigene Inkremente. Der Native-Build ist
 * lokal per GraalVM verifiziert; ein CI-Gate braucht die GraalVM-Toolchain (Plan-Phase D).
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

/** Native-image-Einstieg (`imageName = d-migrate`, `mainClass = dev.dmigrate.cli.NativeMainKt`). */
fun main(args: Array<String>) {
    NativeRoot().subcommands(NativeSchemaGroup().subcommands(NativeSchemaValidate())).main(args)
}
