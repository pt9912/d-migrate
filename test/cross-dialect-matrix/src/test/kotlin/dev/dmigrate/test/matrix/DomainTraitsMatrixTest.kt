package dev.dmigrate.test.matrix

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.mssql.MssqlDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.oracle.OracleDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * `DEFAULT`, `NOT NULL` und Kollation einer Domain ueber die Dialekte (Issue #59), Datei gegen Datei
 * mit dem ziel-bewussten Vergleich, den der Befehl benutzt.
 *
 * Nur PostgreSQL fuehrt die Felder: eine Aenderung daran plant `AlterCustomType`, den PostgreSQL nicht
 * rendert (Exit 8). Jeder andere Dialekt ignoriert sie -- kein Unterschied, kein Neubau, Exit 0.
 *
 * Die Matrix sieht den Reader **nicht** (Datei gegen Datei, keine Datenbank): dass `typnotnull`,
 * `typcollation` und `domain_default` gelesen werden und `required` aus `attnotnull` kommt, belegen
 * `PostgresSchemaReaderTest` und `PostgresDomainTraitsIntegrationTest`.
 */
class DomainTraitsMatrixTest : FunSpec({

    fun load(name: String) = checkNotNull(
        DomainTraitsMatrixTest::class.java.getResource("/fixtures/H.domain-traits/$name.yaml"),
    ) { "fixture $name missing" }.readText()

    fun run(dialect: DatabaseDialect, currentYaml: String, desiredYaml: String): Int {
        val tmp = Files.createTempDirectory("matrix-domain-traits-")
        try {
            val source = tmp.resolve("source.yaml").also { Files.writeString(it, desiredYaml) }
            val target = tmp.resolve("target.yaml").also { Files.writeString(it, currentYaml) }
            val validator = SchemaValidator()
            val runner = SchemaMigrateRunner(
                fileLoader = { op ->
                    val schema = SchemaFileResolver.codecForPath(op.path).read(op.path)
                    ResolvedSchemaOperand(op.path.toString(), schema, validator.validate(schema))
                },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { a, b, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(a, b)
                },
                rendererFor = MatrixSweepRunner().rendererFor,
                atomicWriter = { _, _ -> },
                renderReport = { report, _ -> report.status.toString() },
                printError = { _, _ -> },
                stdout = { },
                stderr = { },
            )
            return runner.execute(
                SchemaMigrateRequest(source.toString(), target.toString(), dialect = dialect, planOnly = true),
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("PostgreSQL: eine geaenderte Domain-Eigenschaft ist sichtbar und blockiert (Exit 8)") {
        run(DatabaseDialect.POSTGRESQL, load("current"), load("desired")) shouldBe 8
    }

    test("PostgreSQL: dieselbe Datei auf beiden Seiten plant nichts (Exit 0)") {
        run(DatabaseDialect.POSTGRESQL, load("desired"), load("desired")) shouldBe 0
    }

    listOf(
        DatabaseDialect.MYSQL, DatabaseDialect.SQLITE, DatabaseDialect.MSSQL, DatabaseDialect.ORACLE,
    ).forEach { dialect ->
        test("$dialect ignoriert default, not_null und collation einer Domain (Exit 0, kein Neubau)") {
            run(dialect, load("current"), load("desired")) shouldBe 0
        }
    }

    test("schema generate: MySQL, SQLite, SQL Server und Oracle rendern eine Domain mit und ohne die drei Felder gleich") {
        fun schema(withTraits: Boolean) = SchemaDefinition(
            name = "s", version = "1",
            customTypes = mapOf(
                "code" to CustomTypeDefinition(
                    kind = CustomTypeKind.DOMAIN, baseType = "text", check = "length(VALUE) > 0",
                    default = if (withTraits) DefaultValue.StringLiteral("x") else null,
                    notNull = withTraits,
                    collation = if (withTraits) "C" else null,
                ),
            ),
            tables = mapOf(
                "items" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "label" to ColumnDefinition(NeutralType.Enum(refType = "code")),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        listOf(
            "MySQL" to MysqlDdlGenerator(),
            "SQLite" to SqliteDdlGenerator(),
            "SQL Server" to MssqlDdlGenerator(),
            "Oracle" to OracleDdlGenerator(),
        ).forEach { (name, generator) ->
            withClue(name) {
                fun ddl(withTraits: Boolean) = generator.generate(schema(withTraits)).statements
                    .map { it.sql }.filterNot { it.startsWith("-- Generated by") } // Der Kopf traegt einen Zeitstempel.
                ddl(withTraits = true) shouldBe ddl(withTraits = false)
            }
        }
        // PostgreSQL trägt sie dagegen.
        PostgresDdlGenerator().generate(schema(withTraits = true)).render() shouldContain
            """CREATE DOMAIN "code" AS TEXT COLLATE "C" DEFAULT 'x' NOT NULL CHECK (length(VALUE) > 0);"""
    }
})
