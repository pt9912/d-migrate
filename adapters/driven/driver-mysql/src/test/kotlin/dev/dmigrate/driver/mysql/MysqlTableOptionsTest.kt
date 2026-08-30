package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlTableOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Storage Engine, Zeichensatz und Kollation standen fest im Generator. Sie
 * beschreiben aber das Ziel, nicht den einzelnen Aufruf — eine Datenbank auf
 * MyISAM oder mit anderer Kollation liess sich gar nicht ausdruecken.
 */
class MysqlTableOptionsTest : FunSpec({

    val generator = MysqlDdlGenerator()
    val schema = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf(
            "users" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
                primaryKey = listOf("id"),
            ),
        ),
    )

    test("without configuration the DDL is unchanged") {
        generator.generate(schema).render() shouldContain
            "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
    }

    test("the configured options reach the table") {
        val ddl = generator.generate(
            schema,
            DdlGenerationOptions(
                dialectContext = DdlDialectContext.MySql(
                    tableOptions = MysqlTableOptions(
                        engine = "MyISAM", charset = "latin1", collation = "latin1_german2_ci",
                    ),
                ),
            ),
        ).render()
        ddl shouldContain "ENGINE=MyISAM DEFAULT CHARSET=latin1 COLLATE=latin1_german2_ci"
    }
})
