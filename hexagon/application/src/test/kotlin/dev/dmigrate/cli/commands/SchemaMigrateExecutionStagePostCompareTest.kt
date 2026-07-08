package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * v7 (Typ-Kanonisierungs-Slice AP3): pins that [SchemaMigrateExecutionStage.runPostCompare]
 * threads the target-dialect type canonicalisation into BOTH fingerprint
 * operands — a dialect-flattened type (desired `smallint` vs. reversed
 * `integer`) reads as Clean WITH the projection and as Drift without it
 * (identity default).
 */
class SchemaMigrateExecutionStagePostCompareTest : FunSpec({

    fun schemaWith(t: NeutralType) = SchemaDefinition(
        name = "s",
        version = "1",
        tables = mapOf("probe" to TableDefinition(columns = mapOf("val" to ColumnDefinition(t)))),
    )

    // Reverse liest die Storage-Klasse zurück; das Soll trägt den Neutraltyp.
    val observed = schemaWith(NeutralType.Integer)
    val desired = schemaWith(NeutralType.SmallInt)

    fun stage() = SchemaMigrateExecutionStage(
        executor = null,
        dbLoader = { _, _ ->
            ResolvedSchemaOperand(
                reference = "db:test",
                schema = observed,
                validation = ValidationResult(),
                dialect = DatabaseDialect.SQLITE,
            )
        },
        normalizer = { it },
        fingerprint = MigrationFingerprint::compute,
        printError = { _, _ -> },
    )

    val request = SchemaMigrateRequest(
        source = "file:src",
        target = "db:test",
        dialect = DatabaseDialect.SQLITE,
        execute = true,
    )
    val target = CompareOperand.Database("db:test")
    val sqliteLike: (NeutralType) -> NeutralType =
        { t -> if (t == NeutralType.SmallInt) NeutralType.Integer else t }

    test("post-compare with target canonicalisation reads a flattened type as Clean") {
        stage().runPostCompare(request, desired, target, sqliteLike)
            .shouldBeInstanceOf<PostCompareOutcome.Clean>()
    }

    test("post-compare without canonicalisation (identity default) reports Drift") {
        stage().runPostCompare(request, desired, target)
            .shouldBeInstanceOf<PostCompareOutcome.Drift>()
    }
})
