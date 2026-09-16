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

        test("every ValueChange field of a column yields one finding") {
            val column = instance(ColumnDiff::class.java) { field, fieldType ->
                when {
                    fieldType == String::class.java -> "c"
                    field == "required" || field == "unique" -> ValueChange(false, true)
                    else -> marker(field)
                }
            }
            val findings = TableCompareFindings.of(TableDiff(name = "t", columnsChanged = listOf(column)))
            findings.size shouldBe valueChangeFields(ColumnDiff::class.java).size
        }

        /**
         * `partitioning` hat noch keinen Fund — ein bekannter, offener Punkt
         * des Compare-Slices („Eine Partitionierungs-Aenderung hat keinen
         * MCP-Fund"). Kommt ein Fund dazu, faellt er hier aus der Liste.
         */
        val tableFieldsWithoutFinding = setOf("partitioning")

        test("every other ValueChange field of a table yields one finding") {
            val table = instance(TableDiff::class.java) { field, fieldType ->
                when (fieldType) {
                    String::class.java -> "t"
                    ValueChange::class.java -> marker(field)
                    List::class.java -> emptyList<Any>()
                    Map::class.java -> emptyMap<String, Any>()
                    else -> error("unexpected field $field: $fieldType")
                }
            }
            val expected = valueChangeFields(TableDiff::class.java) - tableFieldsWithoutFinding
            TableCompareFindings.of(table).size shouldBe expected.size
        }

        test("the known gap is still a gap") {
            val table = TableDiff(name = "t", partitioning = ValueChange(null, null))
            TableCompareFindings.of(table).size shouldBe 0
        }
    }
})
