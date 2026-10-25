package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** `CREATE DOMAIN` mit `COLLATE`, `DEFAULT` und `NOT NULL` (Issue #59). */
class PostgresDdlGeneratorDomainTraitsTest : FunSpec({

    val generator = PostgresDdlGenerator()

    fun render(type: CustomTypeDefinition): String =
        generator.generate(
            SchemaDefinition(name = "s", version = "1", customTypes = mapOf("d" to type)),
        ).render()

    test("alle drei Felder stehen in der Reihenfolge der Grammatik: COLLATE, DEFAULT, NOT NULL, CHECK") {
        render(
            CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "text", check = "length(VALUE) > 0",
                default = DefaultValue.StringLiteral("x"), notNull = true, collation = "C",
            ),
        ) shouldContain """CREATE DOMAIN "d" AS TEXT COLLATE "C" DEFAULT 'x' NOT NULL CHECK (length(VALUE) > 0);"""
    }

    test("ein Zahlen-Default und eine Schema-qualifizierte Kollation") {
        render(
            CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "integer", default = DefaultValue.NumberLiteral(5),
            ),
        ) shouldContain """CREATE DOMAIN "d" AS INTEGER DEFAULT 5;"""
        render(
            CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "text", collation = "app.ci"),
        ) shouldContain """CREATE DOMAIN "d" AS TEXT COLLATE "app"."ci";"""
    }

    test("ohne die Felder bleibt das DDL wie zuvor") {
        render(CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "INTEGER", check = "VALUE > 0")) shouldContain
            """CREATE DOMAIN "d" AS INTEGER CHECK (VALUE > 0);"""
        render(CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "integer"))
            .lines().first { it.startsWith("CREATE DOMAIN") } shouldBe """CREATE DOMAIN "d" AS INTEGER;"""
    }
})
