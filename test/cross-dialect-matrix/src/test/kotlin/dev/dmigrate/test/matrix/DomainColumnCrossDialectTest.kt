package dev.dmigrate.test.matrix

import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.mssql.MssqlDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import dev.dmigrate.format.SchemaFileResolver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

/**
 * Eine Spalte vom Typ einer Domain, wie der PostgreSQL-Reverse sie schreibt (`type: enum` + `ref_type`, Issue #68),
 * gegen `schema generate` der anderen Dialekte: Basistyp plus `CHECK` der Domain an der Spalte; ein Basistyp ohne
 * Gegenstueck bleibt E053, `timestamptz` auf MySQL meldet W100. PostgreSQL erzeugt die Spalte vom Domain-Typ.
 */
class DomainColumnCrossDialectTest : FunSpec({

    val schema = run {
        val text = DomainColumnCrossDialectTest::class.java
            .getResourceAsStream("/fixtures/domain-column/pg-reverse.yaml")!!.readBytes().decodeToString()
        val path = Files.createTempFile("pg-reverse-domain-column", ".yaml")
        try {
            Files.writeString(path, text)
            SchemaFileResolver.codecForPath(path).read(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    fun generate(generator: DdlGenerator) = generator.generate(schema, DdlGenerationOptions())

    test("die Reverse-Datei mit Domain-Spalten besteht die Validierung") {
        SchemaValidator().validate(schema).errors shouldBe emptyList()
    }

    test("postgresql: die Spalte hat den Domain-Typ") {
        val sql = generate(PostgresDdlGenerator()).statements.joinToString("\n") { it.sql }
        sql shouldContain "\"c_int\" \"dd_int\""
        sql shouldContain "\"c_arr\" \"dd_arr\""
    }

    val others = mapOf(
        "mysql" to { MysqlDdlGenerator() as DdlGenerator },
        "sqlite" to { SqliteDdlGenerator() as DdlGenerator },
        "mssql" to { MssqlDdlGenerator() as DdlGenerator },
    )
    for ((name, factory) in others) {
        val result = generate(factory())
        val sql = result.statements.joinToString("\n") { it.sql }

        test("$name: Basistyp und CHECK der Domain stehen an der Spalte, der Domain-Name nicht") {
            // Die Kommentarzeilen nennen die Domain (`-- Domain type "dd_int" is mapped ...`); die Spalte nicht.
            val code = sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            withClue(sql) {
                code shouldContain "CHECK"
                code shouldNotContain "dd_int"
                code shouldNotContain "dd_text"
            }
        }

        test("$name: ein Array-Basistyp ist E053, die uebrigen Domains nicht") {
            val e053 = result.notes.filter { it.code == "E053" }.map { it.objectName.orEmpty() }
            withClue("$e053\n$sql") {
                e053.any { it.endsWith("c_arr") } shouldBe true
                e053.none { it.endsWith("c_int") || it.endsWith("c_text") || it.endsWith("c_dec") } shouldBe true
            }
        }
    }

    test("mysql: eine Domain ueber timestamptz meldet W100 wie eine gewoehnliche Spalte") {
        val w100 = generate(MysqlDdlGenerator()).notes.filter { it.code == "W100" }.map { it.objectName.orEmpty() }
        withClue("$w100") { w100.any { it.endsWith("c_tz") } shouldBe true }
    }
})
