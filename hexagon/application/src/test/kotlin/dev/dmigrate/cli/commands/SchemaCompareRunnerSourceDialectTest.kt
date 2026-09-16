package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.ReverseMarkerNormalizer
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Der Runner reicht jeder Seite den Dialekt mit, aus dem sie zurueckgelesen
 * wurde. Er steht in der Reverse-Markierung — die der Normalizer vor dem
 * Vergleich entfernt; gelesen wird sie deshalb am **unbereinigten** Operanden.
 */
class SchemaCompareRunnerSourceDialectTest : FunSpec({

    fun reversed(name: String) = SchemaDefinition(name = name, version = ReverseScopeCodec.REVERSE_VERSION)

    fun runWith(source: SchemaDefinition, target: SchemaDefinition, dbSource: SchemaDefinition? = null): List<CompareSide> {
        val seen = mutableListOf<CompareSide>()
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                val schema = if (op.path.toString().endsWith("a.yaml")) source else target
                ResolvedSchemaOperand(op.path.toString(), schema, ValidationResult())
            },
            dbLoader = { op, _ -> ResolvedSchemaOperand(op.source, dbSource!!, ValidationResult()) },
            comparator = { left, right ->
                seen += left
                seen += right
                SchemaDiff()
            },
            projectDiff = { DiffView() },
            renderPlain = { it.status },
            renderJson = { it.status },
            renderYaml = { it.status },
            printError = { _, _ -> },
            stdout = {},
            stderr = {},
        )
        val sourceRef = if (dbSource != null) "db:pg" else "/tmp/a.yaml"
        runner.execute(SchemaCompareRequest(sourceRef, "/tmp/b.yaml", null, "plain", quiet = true)) shouldBe 0
        return seen
    }

    test("two reverse artifacts carry their dialects, and the schemas arrive normalized") {
        val (left, right) = runWith(
            reversed(ReverseScopeCodec.postgresName("shop", "public")),
            reversed(ReverseScopeCodec.mysqlName("shop")),
        )
        left.sourceDialect shouldBe DatabaseDialect.POSTGRESQL
        right.sourceDialect shouldBe DatabaseDialect.MYSQL
        left.schema.name shouldBe ReverseMarkerNormalizer.NORMALIZED_NAME
        right.schema.name shouldBe ReverseMarkerNormalizer.NORMALIZED_NAME
    }

    test("a hand-written schema has no dialect") {
        val (left, right) = runWith(
            SchemaDefinition(name = "shop", version = "1"),
            reversed(ReverseScopeCodec.oracleName("SHOP")),
        )
        left.sourceDialect.shouldBeNull()
        right.sourceDialect shouldBe DatabaseDialect.ORACLE
    }

    test("a database operand carries the dialect of its reverse, too") {
        val (left, _) = runWith(
            source = SchemaDefinition(name = "unused", version = "1"),
            target = SchemaDefinition(name = "shop", version = "1"),
            dbSource = reversed(ReverseScopeCodec.mssqlName("shop", "dbo")),
        )
        left.sourceDialect shouldBe DatabaseDialect.MSSQL
    }
})
