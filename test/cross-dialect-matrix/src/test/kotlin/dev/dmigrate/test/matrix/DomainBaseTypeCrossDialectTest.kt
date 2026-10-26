package dev.dmigrate.test.matrix

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.mssql.MssqlDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.oracle.OracleDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import dev.dmigrate.format.SchemaFileResolver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Der Basistyp einer PostgreSQL-Domain mit Laenge oder Array in der Reverse-Datei (Issue #58) geht
 * gegen `schema generate` jedes anderen Dialekts nicht in einen neuen E053.
 *
 * Die Fixture `domain-base-type/pg-reverse.yaml` traegt die Basistypen genau so, wie der
 * PostgreSQL-Reader sie schreibt (`varchar(50)`, `char(5)`, `timestamp(3)`, `integer[]`). Der Reader
 * lieferte vorher `text` fuer `varchar(50)`/`char(5)` und `timestamp` fuer `timestamp(3)`; die Typen mit
 * Laenge muessen weiter einen neutralen Typ ergeben. Ein Array-Basistyp (vorher `_int4`, auch ohne
 * Gegenstueck) bleibt E053 -- das Modell kennt keinen Array-Typ ausserhalb von PostgreSQL.
 */
class DomainBaseTypeCrossDialectTest : FunSpec({

    val schema = run {
        val text = DomainBaseTypeCrossDialectTest::class.java
            .getResourceAsStream("/fixtures/domain-base-type/pg-reverse.yaml")!!.readBytes().decodeToString()
        val path = Files.createTempFile("pg-reverse-domain", ".yaml")
        try {
            Files.writeString(path, text)
            SchemaFileResolver.codecForPath(path).read(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    val targets: Map<DatabaseDialect, () -> DdlGenerator> = mapOf(
        DatabaseDialect.MYSQL to { MysqlDdlGenerator() },
        DatabaseDialect.SQLITE to { SqliteDdlGenerator() },
        DatabaseDialect.MSSQL to { MssqlDdlGenerator() },
        DatabaseDialect.ORACLE to { OracleDdlGenerator() },
    )

    val withLength = listOf("d_varchar", "d_char", "d_varchar_plain", "d_timestamp", "d_timestamptz", "d_time", "d_text")
    val arrays = listOf("d_int_array", "d_varchar_array", "d_numeric_array")

    for ((dialect, generator) in targets) {
        val result = generator().generate(schema, DdlGenerationOptions())
        val e053 = result.notes.filter { it.code == "E053" }.map { it.objectName.orEmpty() }
        val sql = result.statements.joinToString("\n") { it.sql }

        if (dialect == DatabaseDialect.ORACLE) {
            // Oracle schreibt jede Domain als CLOB und weist sie als E053 aus -- unabhaengig vom Basistyp,
            // auch fuer `text`. Die Laenge aendert daran nichts.
            test("oracle: der Basistyp aendert die Ausweisung der Domain nicht") {
                for (name in withLength + arrays) {
                    withClue("$name in $e053\n$sql") { e053.any { it.endsWith("c_$name") } shouldBe true }
                }
            }
        } else {
            test("${dialect.name.lowercase()}: ein Basistyp mit Laenge ergibt keinen E053") {
                for (name in withLength) {
                    withClue("$name in $e053\n$sql") { e053.none { it.endsWith("c_$name") } shouldBe true }
                }
            }
        }

        // Oracle schreibt jede Domain als CLOB und ist hier ausgenommen.
        if (dialect == DatabaseDialect.SQLITE || dialect == DatabaseDialect.MSSQL || dialect == DatabaseDialect.MYSQL) {
            test("${dialect.name.lowercase()}: ein Array-Basistyp bleibt E053, wie zuvor `_int4`") {
                for (name in arrays) {
                    withClue("$name in $e053\n$sql") { e053.any { it.endsWith("c_$name") } shouldBe true }
                }
            }
        }
    }
})
