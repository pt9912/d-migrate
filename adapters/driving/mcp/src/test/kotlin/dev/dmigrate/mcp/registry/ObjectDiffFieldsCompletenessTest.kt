package dev.dmigrate.mcp.registry

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.CustomTypeDiff
import dev.dmigrate.core.diff.FunctionDiff
import dev.dmigrate.core.diff.ProcedureDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.TriggerDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableMetadata
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier

/**
 * Jedes `ValueChange`-Feld eines Diffs ergibt einen `schema_compare`-Fund.
 *
 * Ein neues Feld, das [ObjectDiffFields] oder [TableCompareFindings] nicht
 * kennt, liesse `status: different` ohne einen Eintrag in `findings` stehen —
 * der Abnehmer saehe, **dass** etwas anders ist, aber nicht was. Die Felder
 * werden per Reflection gezaehlt, damit ein solches Feld hier rot wird und
 * nicht erst beim Abnehmer auffaellt.
 */
class ObjectDiffFieldsCompletenessTest : FunSpec({

    fun fieldsOf(type: Class<*>) = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }

    fun valueChangeFields(type: Class<*>): List<String> =
        fieldsOf(type).filter { it.type == ValueChange::class.java }.map { it.name }

    /**
     * Eine Instanz von [type] ueber den Primaerkonstruktor; [value] liefert
     * je Feldname den Wert. Die Felder einer Datenklasse stehen in der
     * Reihenfolge ihrer Konstruktorparameter.
     */
    fun <T> instance(type: Class<T>, value: (field: String, fieldType: Class<*>) -> Any?): T {
        val constructor = type.declaredConstructors.single { c ->
            c.parameterTypes.none { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
        }
        val fields = fieldsOf(type)
        fields.map { it.type } shouldBe constructor.parameterTypes.toList()
        return type.cast(constructor.newInstance(*fields.map { value(it.name, it.type) }.toTypedArray()))
    }

    /** Ein eigener Wert je Feld; die Spalten einer Sicht sind eine Namensliste. */
    fun marker(field: String): ValueChange<*> =
        if (field == "columns") ValueChange(listOf("before"), listOf("after")) else ValueChange("before-$field", "after-$field")

    /** Alle Felder gesetzt: der Name, jedes `ValueChange` mit einem eigenen Wert. */
    fun <T> everyField(type: Class<T>): T = instance(type) { field, fieldType ->
        when (fieldType) {
            String::class.java -> "obj"
            ValueChange::class.java -> marker(field)
            else -> error("unexpected field $field: $fieldType in $type")
        }
    }

    context("die Objekte ausserhalb von Tabellen") {

        /** Je Klasse: ihre Feldliste und ein Vergleich, der nur dieses Objekt aendert. */
        class Kind(
            val type: Class<*>,
            val fields: (Any?) -> List<Pair<String, ValueChange<*>?>>,
            val diff: (Any?) -> SchemaDiff,
        )

        val objects = listOf(
            Kind(ViewDiff::class.java, { ObjectDiffFields.of(it as ViewDiff) }) {
                SchemaDiff(viewsChanged = listOf(it as ViewDiff))
            },
            Kind(SequenceDiff::class.java, { ObjectDiffFields.of(it as SequenceDiff) }) {
                SchemaDiff(sequencesChanged = listOf(it as SequenceDiff))
            },
            Kind(CustomTypeDiff::class.java, { ObjectDiffFields.of(it as CustomTypeDiff) }) {
                SchemaDiff(customTypesChanged = listOf(it as CustomTypeDiff))
            },
            Kind(FunctionDiff::class.java, { ObjectDiffFields.of(it as FunctionDiff) }) {
                SchemaDiff(functionsChanged = listOf(it as FunctionDiff))
            },
            Kind(ProcedureDiff::class.java, { ObjectDiffFields.of(it as ProcedureDiff) }) {
                SchemaDiff(proceduresChanged = listOf(it as ProcedureDiff))
            },
            Kind(TriggerDiff::class.java, { ObjectDiffFields.of(it as TriggerDiff) }) {
                SchemaDiff(triggersChanged = listOf(it as TriggerDiff))
            },
        )

        test("every ValueChange field of the six diff classes has a document key") {
            for (kind in objects) {
                withClue(kind.type.simpleName) {
                    val listed = kind.fields(everyField(kind.type))
                    listed.map { it.second } shouldContainExactlyInAnyOrder valueChangeFields(kind.type).map(::marker)
                    listed.map { it.first }.toSet().size shouldBe listed.size
                }
            }
        }

        test("every field yields exactly one finding") {
            for (kind in objects) {
                withClue(kind.type.simpleName) {
                    val findings = SchemaCompareFindings.of(kind.diff(everyField(kind.type)))
                    findings.size shouldBe valueChangeFields(kind.type).size
                }
            }
        }
    }

    context("Tabellen und Spalten") {

        /** Der Dokument-Schluessel, auf dem der Fund je `ColumnDiff`-Feld endet. */
        val columnKeys = mapOf(
            "type" to "type", "required" to "required", "default" to "default",
            "unique" to "unique", "references" to "references", "generation" to "generation",
        )

        test("every ValueChange field of a column yields exactly one finding at its own key") {
            valueChangeFields(ColumnDiff::class.java).toSet() shouldBe columnKeys.keys
            for (field in valueChangeFields(ColumnDiff::class.java)) {
                withClue(field) {
                    val column = instance(ColumnDiff::class.java) { name, fieldType ->
                        when {
                            fieldType == String::class.java -> "c"
                            name != field -> null
                            name == "required" || name == "unique" -> ValueChange(false, true)
                            else -> marker(name)
                        }
                    }
                    val findings = TableCompareFindings.of(TableDiff(name = "t", columnsChanged = listOf(column)))
                    findings.map { it["path"] } shouldBe listOf("tables.t.columns.c.${columnKeys.getValue(field)}")
                }
            }
        }

        /**
         * `partitioning` hat noch keinen Fund — ein bekannter, offener Punkt
         * des Compare-Slices („Eine Partitionierungs-Aenderung hat keinen
         * MCP-Fund"). Kommt ein Fund dazu, faellt er hier aus der Liste.
         */
        val tableFieldsWithoutFinding = setOf("partitioning")

        val column = ColumnDefinition(NeutralType.Integer)
        val index = IndexDefinition(name = "ix", columns = listOf(IndexColumn("c")))
        val check = ConstraintDefinition(name = "ck", type = ConstraintType.CHECK, expression = "c > 0")

        /**
         * Je Feld von [TableDiff] — auch die listen- und map-wertigen — ein
         * Wert mit **einem** Eintrag und der Pfad, den sein Fund traegt.
         */
        val tableFields: Map<String, Pair<Any, String>> = mapOf(
            "columnsAdded" to (mapOf("c" to column) to "tables.t.columns.c"),
            "columnsRemoved" to (mapOf("c" to column) to "tables.t.columns.c"),
            "columnsChanged" to (
                listOf(ColumnDiff("c", type = ValueChange(NeutralType.Integer, NeutralType.BigInteger))) to
                    "tables.t.columns.c.type"
                ),
            "primaryKey" to (ValueChange(listOf("a"), listOf("b")) to "tables.t.primary_key"),
            "indicesAdded" to (listOf(index) to "tables.t.indices.ix"),
            "indicesRemoved" to (listOf(index) to "tables.t.indices.ix"),
            "indicesChanged" to (listOf(ValueChange(index, index.copy(unique = true))) to "tables.t.indices.ix"),
            "constraintsAdded" to (listOf(check) to "tables.t.constraints.ck"),
            "constraintsRemoved" to (listOf(check) to "tables.t.constraints.ck"),
            "constraintsChanged" to (
                listOf(ValueChange(check, check.copy(expression = "c > 1"))) to "tables.t.constraints.ck"
                ),
            "metadata" to (
                ValueChange(TableMetadata(engine = "InnoDB"), TableMetadata(engine = "MyISAM")) to "tables.t.metadata"
                ),
        )

        test("every field of a table is listed here or a known gap") {
            (fieldsOf(TableDiff::class.java).map { it.name } - "name").toSet() shouldBe
                tableFields.keys + tableFieldsWithoutFinding
        }

        test("every other field of a table yields exactly one finding at its own path") {
            for ((field, entry) in tableFields) {
                withClue(field) {
                    val table = instance(TableDiff::class.java) { name, fieldType ->
                        when {
                            name == "name" -> "t"
                            name == field -> entry.first
                            fieldType == List::class.java -> emptyList<Any>()
                            fieldType == Map::class.java -> emptyMap<String, Any>()
                            else -> null
                        }
                    }
                    table.hasChanges() shouldBe true
                    TableCompareFindings.of(table).map { it["path"] } shouldBe listOf(entry.second)
                }
            }
        }

        test("the known gap is still a gap") {
            val table = TableDiff(name = "t", partitioning = ValueChange(null, null))
            TableCompareFindings.of(table).size shouldBe 0
        }
    }
})
