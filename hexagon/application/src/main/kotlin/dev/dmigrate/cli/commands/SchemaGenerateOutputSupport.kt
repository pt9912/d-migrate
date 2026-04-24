package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.MysqlNamedSequenceMode
import java.nio.file.Path

internal data class GeneratedDdl(
    val generator: DdlGenerator,
    val schema: SchemaDefinition,
    val result: DdlResult,
    val dialect: DatabaseDialect,
    val ddl: String,
    val options: DdlGenerationOptions,
)

internal class SchemaGenerateOutputWriter(
    private val fileWriter: (Path, String) -> Unit,
    private val reportWriter: (Path, DdlResult, SchemaDefinition, String, Path, String?, MysqlNamedSequenceMode?) -> Unit,
    private val sidecarPath: (Path, String) -> Path,
    private val rollbackPath: (Path) -> Path,
    private val splitPath: (Path, DdlPhase) -> Path,
    private val stdout: (String) -> Unit,
    private val stderr: (String) -> Unit,
) {

    fun writeSplitFileOutput(
        request: SchemaGenerateRequest,
        result: DdlResult,
        schema: SchemaDefinition,
        dialect: String,
        splitModeStr: String?,
        mysqlSeqMode: MysqlNamedSequenceMode? = null,
    ) {
        val outputPath = request.output!!
        val prePath = splitPath(outputPath, DdlPhase.PRE_DATA)
        val postPath = splitPath(outputPath, DdlPhase.POST_DATA)
        val preDdl = result.renderPhase(DdlPhase.PRE_DATA)
        val postDdl = result.renderPhase(DdlPhase.POST_DATA)
        fileWriter(prePath, preDdl + "\n")
        if (!request.quiet) stderr("Pre-data DDL written to $prePath")
        fileWriter(postPath, postDdl + "\n")
        if (!request.quiet) stderr("Post-data DDL written to $postPath")

        writeReport(request, result, schema, dialect, outputPath, splitModeStr, mysqlSeqMode)
    }

    fun writeFileOutput(request: SchemaGenerateRequest, gen: GeneratedDdl, splitModeStr: String?) {
        val outputPath = request.output!!
        fileWriter(outputPath, gen.ddl + "\n")
        if (!request.quiet) stderr("DDL written to $outputPath")

        if (request.generateRollback) {
            val rollbackResult = gen.generator.generateRollback(gen.schema, gen.options)
            val rbPath = rollbackPath(outputPath)
            fileWriter(rbPath, rollbackResult.render() + "\n")
            if (!request.quiet) stderr("Rollback DDL written to $rbPath")
        }

        writeReport(
            request,
            gen.result,
            gen.schema,
            gen.dialect.name.lowercase(),
            outputPath,
            splitModeStr,
            gen.options.mysqlNamedSequenceMode,
        )
    }

    fun writeStdoutOutput(request: SchemaGenerateRequest, gen: GeneratedDdl) {
        stdout(gen.ddl)
        if (request.generateRollback) {
            stdout("\n-- ═══════════════════════════════════════")
            stdout("-- ROLLBACK")
            stdout("-- ═══════════════════════════════════════\n")
            stdout(gen.generator.generateRollback(gen.schema, gen.options).render())
        }

        if (request.report != null) {
            writeReport(
                request,
                gen.result,
                gen.schema,
                gen.dialect.name.lowercase(),
                request.report,
                null,
                gen.options.mysqlNamedSequenceMode,
            )
        }
    }

    private fun writeReport(
        request: SchemaGenerateRequest,
        result: DdlResult,
        schema: SchemaDefinition,
        dialect: String,
        outputPath: Path,
        splitModeStr: String?,
        mysqlSeqMode: MysqlNamedSequenceMode? = null,
    ) {
        val reportPath = request.report ?: sidecarPath(outputPath, ".report.yaml")
        reportWriter(reportPath, result, schema, dialect, request.source, splitModeStr, mysqlSeqMode)
        if (!request.quiet) stderr("Report written to $reportPath")
    }
}
