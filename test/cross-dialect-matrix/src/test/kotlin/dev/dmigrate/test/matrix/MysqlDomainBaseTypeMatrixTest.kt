package dev.dmigrate.test.matrix

import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.format.SchemaFileResolver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * `schema generate` auf MySQL fuer eine Domain-Spalte (Issue #62): der Basistyp steht so in der
 * Datei, wie der PostgreSQL-Reader ihn schreibt (`int4`, `timestamptz(3)`, `integer[]`), und wird nie
 * roh durchgereicht. Ein Basistyp mit neutralem Gegenstueck ergibt dessen MySQL-Typ; einer ohne
 * ergibt genau eine E053-Notiz (`domain`, `t.c_<name>`) und die Spalte `TEXT`.
 *
 * Der Sweep fuehrt kein DDL aus; die Akzeptanz durch MySQL belegt
 * `MysqlDomainBaseTypeIntegrationTest` (`test/integration-mysql`).
 */
class MysqlDomainBaseTypeMatrixTest : FunSpec({

    val schema = run {
        val text = MysqlDomainBaseTypeMatrixTest::class.java
            .getResourceAsStream("/fixtures/domain-base-type-mysql/reverse.yaml")!!.readBytes().decodeToString()
        val path = Files.createTempFile("pg-reverse-domain-mysql", ".yaml")
        try {
            Files.writeString(path, text)
            SchemaFileResolver.codecForPath(path).read(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    val result = MysqlDdlGenerator().generate(schema, DdlGenerationOptions())
    val sql = result.statements.joinToString("\n") { it.sql }
    val e053 = result.notes.filter { it.code == "E053" }

    // Spalte -> (MySQL-Typ, E053?)
    val expected = mapOf(
        "int4" to ("INT" to false),
        "int8" to ("BIGINT" to false),
        "bool" to ("TINYINT(1)" to false),
        "float8" to ("DOUBLE" to false),
        "varchar" to ("VARCHAR(50)" to false),
        "bpchar_char" to ("CHAR(5)" to false),
        "numeric_p" to ("DECIMAL(12,4)" to false),
        "uuid" to ("CHAR(36)" to false),
        "jsonb" to ("JSON" to false),
        "bytea" to ("BLOB" to false),
        "timestamp3" to ("DATETIME" to false),
        "time3" to ("TIME" to false),
        "timestamptz3" to ("DATETIME" to false),
        "numeric_plain" to ("TEXT" to true),
        "bit" to ("TEXT" to true),
        "bit3" to ("TEXT" to true),
        "varbit" to ("TEXT" to true),
        "bpchar" to ("TEXT" to true),
        "timetz" to ("TEXT" to true),
        "money" to ("TEXT" to true),
        "interval" to ("TEXT" to true),
        "inet" to ("TEXT" to true),
        "int_array" to ("TEXT" to true),
        "varchar_array" to ("TEXT" to true),
        "numeric_array" to ("TEXT" to true),
    )

    for ((name, spec) in expected) {
        val (sqlType, needsE053) = spec
        test("MySQL: Domain-Basistyp '$name' ergibt $sqlType${if (needsE053) " mit genau einer E053" else ""}") {
            withClue(sql) {
                sql.lines().map { it.trim().trimEnd(',') } shouldContain "`c_$name` $sqlType"
            }
            val own = e053.filter { it.objectName == "t.c_$name" }
            own.size shouldBe (if (needsE053) 1 else 0)
        }
    }

    test("MySQL: die E053-Notizen decken genau die Basistypen ohne Gegenstueck") {
        e053.map { it.objectName }.toSet() shouldBe
            expected.filterValues { it.second }.keys.map { "t.c_$it" }.toSet()
    }

    test("MySQL: nur die Domain ueber timestamptz traegt W100") {
        result.notes.filter { it.code == "W100" }.map { it.objectName } shouldBe listOf("t.c_timestamptz3")
    }
})
