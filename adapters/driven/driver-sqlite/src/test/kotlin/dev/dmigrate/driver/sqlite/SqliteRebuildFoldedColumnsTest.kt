package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Issue #54, Ursache 2: der Neubau uebernimmt die Entscheidung des Vergleichs. Eine Spalte, fuer
 * die der Eimer kein `AlterColumnType` traegt, ist keine Typaenderung — auch wenn SQLite sie
 * enger ablegt, als die Datei sie schreibt (`text(32)` wird als `text` gelesen, `boolean` als
 * `integer`, `decimal` als `float`).
 */
class SqliteRebuildFoldedColumnsTest : FunSpec({

    val sql = SqliteDiffSqlBuilders()

    /** Eine Spalte: was der Reader aus der Datenbank liest, und was die Datei schreibt. */
    data class Folded(val name: String, val read: NeutralType, val written: NeutralType)

    val foldedColumns = listOf(
        Folded("t32", NeutralType.Text(), NeutralType.Text(32)),
        Folded("ch", NeutralType.Text(), NeutralType.Char(10)),
        Folded("dt", NeutralType.Text(), NeutralType.DateTime()),
        Folded("uu", NeutralType.Text(), NeutralType.Uuid),
        Folded("bo", NeutralType.Integer, NeutralType.BooleanType),
        Folded("de", NeutralType.Float(), NeutralType.Decimal(10, 2)),
        Folded("fs", NeutralType.Float(), NeutralType.Float(FloatPrecision.SINGLE)),
        Folded("da", NeutralType.Text(), NeutralType.Date),
        Folded("ti", NeutralType.Text(), NeutralType.Time),
        Folded("dz", NeutralType.Text(), NeutralType.DateTime(timezone = true)),
        Folded("js", NeutralType.Text(), NeutralType.Json),
        Folded("xm", NeutralType.Text(), NeutralType.Xml),
        Folded("em", NeutralType.Text(), NeutralType.Email),
        Folded("ar", NeutralType.Text(), NeutralType.Array("text")),
        Folded("ft", NeutralType.Text(), NeutralType.FullText),
    )

    fun table(vararg columns: Pair<String, NeutralType>) = TableDefinition(
        columns = columns.associate { (name, type) -> name to ColumnDefinition(type) },
    )

    fun alterType(column: String, before: NeutralType, after: NeutralType) = DiffOperation.AlterColumnType(
        id = "alter-$column",
        objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("t", column)),
        before = before,
        after = after,
    )

    fun plan(source: TableDefinition, target: TableDefinition, bucket: List<DiffOperation>) =
        SqliteRebuildPlanner.planRebuild(
            table = "t",
            bucket = bucket,
            sides = SqliteRebuildSides(
                source = source,
                target = target,
            ),
            bucketRisk = OperationRisk.SAFE,
            sql = sql,
        )

    for (col in foldedColumns) {
        test("${col.name}: ohne AlterColumnType im Eimer wird die Spalte kopiert, nicht umgewandelt") {
            val mapping = plan(
                table(col.name to col.read),
                table(col.name to col.written),
                bucket = emptyList(),
            ).mapping

            mapping.castNotWhitelisted.shouldBeEmpty()
            val copy = mapping.preservedColumns.single()
            copy.typeChanged shouldBe false
            copy.expressionSql shouldBe "\"${col.name}\""
        }

        test("${col.name}: meldet der Vergleich die Typaenderung (strikt), entscheidet weiter die Matrix") {
            val blocked = plan(
                table(col.name to col.read),
                table(col.name to col.written),
                bucket = listOf(alterType(col.name, col.read, col.written)),
            ).mapping.castNotWhitelisted

            blocked.map { it.column } shouldBe listOf(col.name)
        }
    }

    test("eine echte Typaenderung neben gefalteten Spalten ist der einzige Eintrag in castNotWhitelisted") {
        val source = table("n" to NeutralType.Integer, *foldedColumns.map { it.name to it.read }.toTypedArray())
        val target = table("n" to NeutralType.Text(), *foldedColumns.map { it.name to it.written }.toTypedArray())

        val mapping = plan(source, target, bucket = listOf(alterType("n", NeutralType.Integer, NeutralType.Text()))).mapping

        mapping.castNotWhitelisted.map { it.column } shouldBe listOf("n")
        mapping.preservedColumns.filter { it.typeChanged }.map { it.targetColumn } shouldBe listOf("n")
    }

    test("eine gemeldete, zulaessige Typaenderung wird weiter mit CAST umgewandelt") {
        val mapping = plan(
            table("age" to NeutralType.SmallInt),
            table("age" to NeutralType.Integer),
            bucket = listOf(alterType("age", NeutralType.SmallInt, NeutralType.Integer)),
        ).mapping

        mapping.castNotWhitelisted.shouldBeEmpty()
        mapping.preservedColumns.single().expressionSql shouldBe "CAST(\"age\" AS INTEGER)"
    }

    test("eine umbenannte Spalte (im Quellschema unbekannt) steht als hinzugefuegt, nicht als Umwandlung") {
        val mapping = plan(
            table("old_t" to NeutralType.Text()),
            table("new_t" to NeutralType.Text(32)),
            bucket = emptyList(),
        ).mapping

        mapping.castNotWhitelisted.shouldBeEmpty()
        mapping.addedColumns.map { it.targetColumn } shouldBe listOf("new_t")
        mapping.droppedColumnNames shouldBe listOf("old_t")
    }

    // ── Domain-Spalten (Review zu #54): der Vergleich meldet den Basistypwechsel nur als AlterCustomType ──

    fun domainSchema(base: String) = SchemaDefinition(
        name = "S", version = "1",
        customTypes = mapOf("pos" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base)),
    )

    fun domainTable() = table("n" to NeutralType.Enum(refType = "pos"))

    fun planDomain(sourceBase: String, targetBase: String) = SqliteRebuildPlanner.planRebuild(
        table = "t",
        bucket = emptyList(),
        sides = SqliteRebuildSides(
            source = domainTable(),
            target = domainTable(),
            sourceSchema = domainSchema(sourceBase),
            targetSchema = domainSchema(targetBase),
        ),
        bucketRisk = OperationRisk.SAFE,
        sql = sql,
    ).mapping

    test("Domain: ein Basistypwechsel text -> integer ohne AlterColumnType blockiert weiter an der Cast-Matrix") {
        val mapping = planDomain("text", "integer")
        mapping.castNotWhitelisted shouldHaveSize 1
        mapping.castNotWhitelisted.single().column shouldBe "n"
    }

    test("Domain: integer -> biginteger ist erlaubt und wird gecastet, nicht blockiert") {
        val mapping = planDomain("integer", "biginteger")
        mapping.castNotWhitelisted.shouldBeEmpty()
    }

    test("Domain: unveraenderter Basistyp wird kopiert") {
        val mapping = planDomain("integer", "integer")
        mapping.castNotWhitelisted.shouldBeEmpty()
        mapping.preservedColumns.single().typeChanged shouldBe false
    }

    test("Umbenennung neben einer gefalteten Spalte: die gefaltete wird kopiert, nicht blockiert") {
        val mapping = plan(
            table("old_n" to NeutralType.Integer, "t32" to NeutralType.Text()),
            table("new_n" to NeutralType.Integer, "t32" to NeutralType.Text(32)),
            bucket = emptyList(),
        ).mapping
        mapping.castNotWhitelisted.shouldBeEmpty()
        mapping.preservedColumns.map { it.sourceColumn } shouldBe listOf("t32")
    }
})
