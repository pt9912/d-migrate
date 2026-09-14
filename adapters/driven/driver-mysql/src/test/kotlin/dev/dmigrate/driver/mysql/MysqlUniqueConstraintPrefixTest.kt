package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * MySQLs Praefixlaengen-Regel (ERROR 1170) trifft **UNIQUE-Constraints**,
 * nicht nur Indizes.
 *
 * Eine unbegrenzte `TEXT`/`BLOB`-Spalte laesst sich in MySQL nicht ohne
 * Praefixlaenge in einen Schluessel aufnehmen. Fuer **Indizes** war das seit
 * I-08 abgefangen (`W125`, `MysqlIndexPartitionDdlHelper`); der
 * Constraint-Pfad rendert `CONSTRAINT … UNIQUE (email)` ungeprueft, und der
 * Server lehnt die **ganze Anweisung** ab — die erzeugte DDL war also nicht
 * anwendbar, ohne dass der Lauf es sagte. Gefunden an einem Konsumenten-Schema
 * (`customers.email` als `TEXT` mit `uq_customer_email`).
 *
 * Die Linie ist dieselbe wie bei MSSQL (E057): **ueberspringen und benennen**,
 * nicht raten. Eine Prefix-Laenge zu erfinden aenderte die Zusicherung — nur
 * die ersten n Zeichen waeren eindeutig —, ohne dass es jemand erfuehre.
 */
class MysqlUniqueConstraintPrefixTest : FunSpec({

    val generator = MysqlDdlGenerator()

    fun schemaWith(emailType: NeutralType) = SchemaDefinition(
        name = "test_schema", version = "1.0",
        tables = mapOf(
            "customers" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Identifier()),
                    "email" to ColumnDefinition(emailType, required = true),
                ),
                constraints = listOf(
                    ConstraintDefinition(
                        name = "uq_customer_email",
                        type = ConstraintType.UNIQUE,
                        columns = listOf("email"),
                    ),
                ),
            ),
        ),
    )

    test("UNIQUE on an unbounded TEXT column is skipped with W125 (I-08 / ERROR 1170)") {
        val result = generator.generate(schemaWith(NeutralType.Text()))

        result.render() shouldNotContain "CONSTRAINT `uq_customer_email`"
        result.notes.any { it.code == "W125" && it.objectName == "uq_customer_email" } shouldBe true
        // Der Ausgang haengt am SkippedObject, nicht an der Notiz — sonst liefe
        // der Lauf mit Exit 0 durch und die Constraint fehlte unbemerkt.
        result.skippedObjects.any { it.name == "uq_customer_email" && it.code == "W125" } shouldBe true
    }

    test("UNIQUE on an unbounded BLOB column is skipped too") {
        val result = generator.generate(schemaWith(NeutralType.Binary))

        result.render() shouldNotContain "CONSTRAINT `uq_customer_email`"
        result.skippedObjects.any { it.name == "uq_customer_email" } shouldBe true
    }

    /**
     * Der Pfad, den ein Reverse tatsaechlich nimmt: ein **benanntes** UNIQUE
     * steht als `unique` + `unique_constraint` an der *Spalte*, nicht als
     * Eintrag in `constraints`. Er laeuft ueber `NamedUniqueConstraints.named`
     * und war von der ersten Fassung dieses Fixes **nicht** erfasst — die
     * erzeugte DDL blieb unveraendert fehlerhaft, obwohl der Test gruen war.
     * Beide Pfade muessen dieselbe Antwort geben.
     */
    test("UNIQUE spelled on the column (unique + unique_constraint) is skipped too") {
        val schema = SchemaDefinition(
            name = "test_schema", version = "1.0",
            tables = mapOf(
                "customers" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier()),
                        "email" to ColumnDefinition(
                            NeutralType.Text(), required = true,
                            unique = true, uniqueConstraintName = "uq_customer_email",
                        ),
                    ),
                ),
            ),
        )
        val result = generator.generate(schema)

        result.render() shouldNotContain "CONSTRAINT `uq_customer_email`"
        result.skippedObjects.any { it.name == "uq_customer_email" && it.code == "W125" } shouldBe true
    }

    test("UNIQUE on a bounded VARCHAR column is emitted normally") {
        val result = generator.generate(schemaWith(NeutralType.Text(maxLength = 255)))

        result.render() shouldContain "CONSTRAINT `uq_customer_email` UNIQUE (`email`)"
        result.skippedObjects.any { it.name == "uq_customer_email" } shouldBe false
    }

    /**
     * Der Gegenfall: Ein gewoehnlicher Index auf derselben Spalte war **schon**
     * abgefangen — die Regel ist also nicht neu, sie fehlte nur an dieser
     * Stelle. Der Test pinnt, dass beide Pfade dieselbe Antwort geben.
     */
    test("the index path answers the same way for the same column") {
        val withIndex = SchemaDefinition(
            name = "test_schema", version = "1.0",
            tables = mapOf(
                "customers" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier()),
                        "email" to ColumnDefinition(NeutralType.Text(), required = true),
                    ),
                    indices = listOf(
                        dev.dmigrate.core.model.IndexDefinition(
                            name = "ix_customer_email",
                            columns = listOf(dev.dmigrate.core.model.IndexColumn("email")),
                        ),
                    ),
                ),
            ),
        )
        val result = generator.generate(withIndex)

        result.notes.any { it.code == "W125" && it.objectName == "ix_customer_email" } shouldBe true
    }
})
