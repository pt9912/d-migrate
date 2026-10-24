package dev.dmigrate.core.diff

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * Der Wertevorrat eines `ref_type`-Enums steht nur in `custom_types`. Ein Dialekt,
 * dessen Reverse ihn als CHECK an der Spalte zurueckgibt (SQLite), hat auf der
 * Ist-Seite ein Vokabular und auf der Soll-Seite keins — ohne die Aufloesung
 * meldeten Vergleich und Abdruck bei jedem Lauf Drift, und eine Aenderung der
 * Werte bliebe unsichtbar.
 */
class RefTypeVocabularyTest : FunSpec({

    fun enumType(vararg values: String) = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = values.toList())

    fun refColumn(ref: String = "ee") = ColumnDefinition(NeutralType.Enum(refType = ref), required = true)

    fun soll(vararg values: String) = SchemaDefinition(
        name = "app", version = "1",
        customTypes = mapOf("ee" to enumType(*values)),
        tables = mapOf("t" to TableDefinition(columns = mapOf("e" to refColumn()))),
    )

    /** Wie der Migrate-Reader die Spalte zurueckgibt: Text plus der synthetische CHECK, ohne Custom Types. */
    fun ist(expression: String = "\"e\" IN ('a', 'b')") = SchemaDefinition(
        name = "app", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf("e" to ColumnDefinition(NeutralType.Text(), required = true)),
                constraints = listOf(
                    ConstraintDefinition(name = "__dm_enum_t_e", type = ConstraintType.CHECK, expression = expression),
                ),
            ),
        ),
    )

    val sqliteLike = TargetProjection(
        type = { if (it is NeutralType.Enum) NeutralType.Text() else it },
        resolvesRefTypeVocabulary = true,
    )

    context("resolve") {
        test("ein ENUM-ref_type wird zum Enum mit Werten") {
            val resolved = RefTypeVocabulary.resolve(soll("a", "b").tables, soll("a", "b").customTypes)

            resolved.getValue("t").columns.getValue("e").type shouldBe NeutralType.Enum(values = listOf("a", "b"))
            resolved.getValue("t").columns.getValue("e").required shouldBe true
        }

        test("Domain, Composite, unbekannter Typ und Inline-Enum bleiben unberuehrt") {
            val tables = mapOf(
                "t" to TableDefinition(
                    columns = mapOf(
                        "d" to refColumn("dom"),
                        "c" to refColumn("comp"),
                        "u" to refColumn("missing"),
                        "i" to ColumnDefinition(NeutralType.Enum(values = listOf("x"))),
                        "n" to ColumnDefinition(NeutralType.Integer),
                    ),
                ),
            )
            val customTypes = mapOf(
                "dom" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE > 0"),
                "comp" to CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE),
            )

            RefTypeVocabulary.resolve(tables, customTypes) shouldBe tables
        }

        test("ohne Custom Types wird nichts kopiert") {
            val tables = soll("a").tables
            RefTypeVocabulary.resolve(tables, emptyMap()) shouldBeSameInstanceAs tables
        }

        test("ein ENUM ohne Werte bleibt unaufgeloest") {
            val tables = soll("a").tables
            val resolved = RefTypeVocabulary.resolve(tables, mapOf("ee" to CustomTypeDefinition(kind = CustomTypeKind.ENUM)))
            resolved.getValue("t").columns.getValue("e").type shouldBe NeutralType.Enum(refType = "ee")
        }

        test("skip nimmt eine Spalte aus") {
            val table = soll("a", "b").tables.getValue("t")
            val resolved = RefTypeVocabulary.resolve(table, soll("a", "b").customTypes) { it == "e" }
            resolved shouldBeSameInstanceAs table
        }
    }

    context("Vergleich gegen eine Datenbank (Ist: Text + CHECK, Soll: ref_type)") {
        test("gleicher Wertevorrat: kein Unterschied") {
            SchemaComparator(sqliteLike).compare(ist(), soll("a", "b")).tablesChanged.shouldBeEmpty()
        }

        test("gleicher Wertevorrat in anderer Schreibweise und Reihenfolge: kein Unterschied") {
            SchemaComparator(sqliteLike)
                .compare(ist("  e IN ( 'b' ,'a' )  "), soll("a", "b")).tablesChanged.shouldBeEmpty()
        }

        test("geaenderter Wertevorrat: eine Aenderung an der Spalte, nicht am Constraint") {
            val table = SchemaComparator(sqliteLike).compare(ist(), soll("a", "b", "c")).tablesChanged.single()

            table.columnsChanged.single().name shouldBe "e"
            table.columnsChanged.single().type.shouldNotBeNull()
            table.constraintsRemoved.shouldBeEmpty()
            table.constraintsAdded.shouldBeEmpty()
        }

        test("ohne das Dialekt-Gate bleibt der Wertevorrat unsichtbar fuer das Soll") {
            val ungated = TargetProjection(type = { if (it is NeutralType.Enum) NeutralType.Text() else it })

            // Ist hat ein Vokabular, Soll keins: genau die Dauer-Drift, die das Gate vermeidet.
            SchemaComparator(ungated).compare(ist(), soll("a", "b")).tablesChanged.single()
                .columnsChanged.single().name shouldBe "e"
        }

        test("schema compare bleibt strikt") {
            SchemaComparator().compare(ist(), soll("a", "b")).tablesChanged.shouldNotBeEmpty()
        }
    }

    context("file:-Ziel — beide Seiten fuehren den ref_type") {
        test("eine Wertaenderung ist Sache der Custom Types, kein zusaetzliches ColumnDiff") {
            val diff = SchemaComparator(sqliteLike).compare(soll("a", "b"), soll("a", "b", "c"))

            diff.tablesChanged.shouldBeEmpty()
            diff.customTypesChanged.single().name shouldBe "ee"
        }

        test("der Vergleich ist mit und ohne Dialekt-Gate derselbe (Operationen und IDs bleiben)") {
            val ungated = TargetProjection(type = { if (it is NeutralType.Enum) NeutralType.Text() else it })

            SchemaComparator(sqliteLike).compare(soll("a", "b"), soll("a", "b", "c")) shouldBe
                SchemaComparator(ungated).compare(soll("a", "b"), soll("a", "b", "c"))
        }
    }

    context("Abdruck") {
        val projections = { type: NeutralType -> if (type is NeutralType.Enum) NeutralType.Text() else type }

        fun fingerprint(schema: SchemaDefinition, resolves: Boolean) = MigrationFingerprint.compute(
            schema, projections, projectsCustomTypes = false, resolvesRefTypeVocabulary = resolves,
        )

        test("Soll und Migrate-Lesung hashen gleich, wenn das Gate gesetzt ist") {
            fingerprint(soll("a", "b"), true) shouldBe fingerprint(ist(), true)
        }

        test("ohne das Gate haben beide Seiten verschiedene Abdruecke (Dauer-Drift)") {
            fingerprint(soll("a", "b"), false) shouldNotBe fingerprint(ist(), false)
        }

        test("ein anderer Wertevorrat aendert den Abdruck") {
            fingerprint(soll("a", "b", "c"), true) shouldNotBe fingerprint(ist(), true)
        }

        test("ein Schema ohne ref_type-Enum hasht mit und ohne Gate gleich") {
            val plain = ist()
            fingerprint(plain, true) shouldBe fingerprint(plain, false)
        }
    }
})
