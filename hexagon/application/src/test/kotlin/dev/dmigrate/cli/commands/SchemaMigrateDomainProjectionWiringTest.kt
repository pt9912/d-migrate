package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Die echte Verdrahtung der Domain-Projektion (Issue #68): nur gegen eine **Datenbank** wird die Datenbank-Seite einer
 * Domain-Spalte ueber den Basistyp projiziert. Ein Datei-Ziel (`current` ist eine Datei) projiziert nicht: eine Datei
 * mit `ref_type` gegen eine Datei mit dem Basistyp bleibt eine Typaenderung.
 */
class SchemaMigrateDomainProjectionWiringTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.loadAll() }
    afterSpec { DatabaseDriverRegistry.clear() }

    val domain = mapOf("dd" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "integer"))
    fun schema(type: NeutralType) = SchemaDefinition(
        name = "s", version = "1", customTypes = domain,
        tables = mapOf("t" to TableDefinition(columns = mapOf("d" to ColumnDefinition(type)))),
    )
    val ref = NeutralType.Enum(refType = "dd")

    val tmp = Files.createTempDirectory("domain-projection")
    val sourcePath = tmp.resolve("source.yaml").also { Files.writeString(it, "# source") }
    val targetPath = tmp.resolve("target.yaml").also { Files.writeString(it, "# target") }

    /** Der Vergleich, den der Runner mit der echten Projektion ausfuehrt; [current] ist das Ziel, [desired] die Datei. */
    fun diff(current: SchemaDefinition, desired: SchemaDefinition, targetIsDatabase: Boolean): SchemaDiff {
        var result: SchemaDiff? = null
        val runner = SchemaMigrateRunner(
            fileLoader = { op ->
                val schema = if (op.path == sourcePath) desired else current
                ResolvedSchemaOperand("file:${op.path.fileName}", schema, ValidationResult(), dialect = null)
            },
            dbLoader = { _, _ ->
                ResolvedSchemaOperand("db:x", current, ValidationResult(), dialect = DatabaseDialect.POSTGRESQL)
            },
            normalizer = { it },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            targetAwareComparator = { l, r, projection, authorship, serverForm ->
                SchemaComparator(projection, authorship, serverForm).compare(l, r).also { result = it }
            },
            rendererFor = { d ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = d
                    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions) =
                        MigrationDdlResult(statements = emptyList(), operationsRendered = emptySet())
                    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions) =
                        MigrationDdlResult(statements = emptyList(), operationsRendered = emptySet())
                }
            },
            atomicWriter = { p, c -> Files.writeString(p, c) },
            renderReport = { _, _ -> "{}" },
            printError = { m, s -> System.err.println("ERR [$s] $m") },
            stdout = { },
            stderr = { },
        )
        runner.execute(
            SchemaMigrateRequest(
                source = sourcePath.toString(),
                target = if (targetIsDatabase) "db:postgresql://u:p@h/db" else targetPath.toString(),
                dialect = DatabaseDialect.POSTGRESQL, planOnly = true,
            ),
        )
        return checkNotNull(result)
    }

    test("Datenbank-Ziel: Datei mit Basistyp gegen Datenbank-Domain-Spalte ist keine Aenderung") {
        diff(schema(ref), schema(NeutralType.Integer), targetIsDatabase = true).tablesChanged shouldBe emptyList()
    }

    test("Datenbank-Ziel: Datei mit ref_type gegen Datenbank-Spalte vom Basistyp bleibt eine Aenderung") {
        diff(schema(NeutralType.Integer), schema(ref), targetIsDatabase = true).tablesChanged.size shouldBe 1
    }

    test("Datei-Ziel: eine Datei mit ref_type gegen eine Datei mit dem Basistyp plant AlterColumnType, in beide Richtungen") {
        diff(schema(ref), schema(NeutralType.Integer), targetIsDatabase = false).tablesChanged.size shouldBe 1
        diff(schema(NeutralType.Integer), schema(ref), targetIsDatabase = false).tablesChanged.size shouldBe 1
    }
})
