package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Der Post-Compare nach `migrate --execute`, wo rohes SQL im Spiel ist.
 *
 * Autorentext und Katalogform desselben Ausdrucks stimmen nie ueberein — der
 * Server druckt aus seinem Parsebaum, nicht aus der Eingabe. Beide
 * gegeneinander zu stellen meldete bei **jedem** Lauf Drift, Exit 5 und kein
 * Ruecknahme-Artefakt ([ADR 0053](../../../../../../../../docs/adr/0053-vergleich-rohen-sql-texts.md)).
 */
class SchemaMigratePostCompareRawSqlTest : FunSpec({

    fun schemaWithCheck(expression: String) = SchemaDefinition(
        name = "s", version = "1",
        tables = mapOf(
            "customers" to TableDefinition(
                columns = mapOf("age" to ColumnDefinition(NeutralType.Integer)),
                constraints = listOf(
                    ConstraintDefinition(name = "chk_age", type = ConstraintType.CHECK, expression = expression),
                ),
            ),
        ),
    )

    val request = SchemaMigrateRequest(
        source = "file:src", target = "db:test", dialect = DatabaseDialect.POSTGRESQL, execute = true,
    )
    val target = CompareOperand.Database("db:test")

    fun stage(
        observed: SchemaDefinition,
        printed: MutableList<String> = mutableListOf(),
        provenanceSink: ProvenanceSink? = null,
    ) =
        SchemaMigrateExecutionStage(
            executor = null,
            dbLoader = { _, _ ->
                ResolvedSchemaOperand(
                    reference = "db:test", schema = observed, validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            normalizer = { it },
            fingerprint = MigrationFingerprint::compute,
            printError = { message, _ -> printed += message },
            provenanceSink = provenanceSink,
        )

    test("Autorentext gegen Katalogform ist keine Drift — sonst meldete jeder Lauf eine") {
        // Live gemessen gegen PostgreSQL 16: `age >= 18` kommt als
        // `((age >= 18))` zurueck.
        val authored = schemaWithCheck("age >= 18")
        val catalog = schemaWithCheck("((age >= 18))")
        val printed = mutableListOf<String>()

        val outcome = stage(catalog, printed).runPostCompare(
            request, authored, target,
            rawSqlBaseline = RawSqlBaseline(observedBeforeRun = catalog, touchedObjects = emptySet()),
        )

        outcome.shouldBeInstanceOf<PostCompareOutcome.Clean>()
        printed.shouldBeEmpty()
    }

    test("eine Handaenderung an einem nicht angefassten Objekt ist Drift") {
        // Zwei Formen desselben Servers: so fuehrte er den CHECK vor dem Lauf,
        // so danach. Der Plan hat die Tabelle nicht angefasst — also hat jemand
        // von Hand geschrieben.
        val before = schemaWithCheck("((age >= 18))")
        val after = schemaWithCheck("((age >= 21))")
        val printed = mutableListOf<String>()

        val outcome = stage(after, printed).runPostCompare(
            request, schemaWithCheck("age >= 18"), target,
            rawSqlBaseline = RawSqlBaseline(observedBeforeRun = before, touchedObjects = emptySet()),
        )

        outcome.shouldBeInstanceOf<PostCompareOutcome.Drift>()
        withClue(printed.toString()) {
            printed.single() shouldContain "chk_age"
            printed.single() shouldContain "((age >= 21))"
        }
    }

    test("dieselbe Abweichung an einem angefassten Objekt ist die Absicht des Laufs") {
        val before = schemaWithCheck("((age >= 18))")
        val after = schemaWithCheck("((age >= 21))")

        val outcome = stage(after).runPostCompare(
            request, schemaWithCheck("age >= 21"), target,
            rawSqlBaseline = RawSqlBaseline(observedBeforeRun = before, touchedObjects = setOf("customers")),
        )

        outcome.shouldBeInstanceOf<PostCompareOutcome.Clean>()
    }

    test("ohne Grundlinie wird die Frage nicht beantwortet, statt sie falsch zu beantworten") {
        val outcome = stage(schemaWithCheck("((age >= 21))")).runPostCompare(
            request, schemaWithCheck("age >= 18"), target,
        )

        outcome.shouldBeInstanceOf<PostCompareOutcome.Clean>()
    }

    test("die Herkunft entsteht nur bei sauberem Vergleich") {
        // Erst dann steht fest, dass das Ziel dem Soll entspricht — und nur
        // dann gehoert das Paar (Autorentext, Katalogform) zusammen.
        val recorded = mutableListOf<Pair<SchemaDefinition, SchemaDefinition>>()
        val catalog = schemaWithCheck("((age >= 18))")

        stage(catalog, provenanceSink = { _, authored, observed -> recorded += authored to observed })
            .runPostCompare(
                request, schemaWithCheck("age >= 18"), target,
                rawSqlBaseline = RawSqlBaseline(observedBeforeRun = catalog, touchedObjects = emptySet()),
            )

        val (authored, observed) = recorded.single()
        // Der Autorentext links, die Katalogform rechts — genau das Paar, das
        // sich spaeter nicht mehr herstellen laesst.
        authored.tables["customers"]!!.constraints.single().expression shouldBe "age >= 18"
        observed.tables["customers"]!!.constraints.single().expression shouldBe "((age >= 18))"
    }

    test("nach einer Drift entsteht keine — sie waere eine Zusage, die der Lauf nicht hat") {
        val recorded = mutableListOf<Pair<SchemaDefinition, SchemaDefinition>>()
        val before = schemaWithCheck("((age >= 18))")
        val after = schemaWithCheck("((age >= 21))")

        stage(after, provenanceSink = { _, a, o -> recorded += a to o }).runPostCompare(
            request, schemaWithCheck("age >= 18"), target,
            rawSqlBaseline = RawSqlBaseline(observedBeforeRun = before, touchedObjects = emptySet()),
        )

        recorded.shouldBeEmpty()
    }

    test("eine echte Strukturaenderung faellt weiterhin auf") {
        // Rohes SQL faellt aus dem Fingerabdruck, alles andere nicht.
        val desired = SchemaDefinition(
            name = "s", version = "1",
            tables = mapOf(
                "customers" to TableDefinition(
                    columns = mapOf(
                        "age" to ColumnDefinition(NeutralType.Integer),
                        "name" to ColumnDefinition(NeutralType.Text(50)),
                    ),
                ),
            ),
        )
        val observed = SchemaDefinition(
            name = "s", version = "1",
            tables = mapOf(
                "customers" to TableDefinition(columns = mapOf("age" to ColumnDefinition(NeutralType.Integer))),
            ),
        )

        stage(observed).runPostCompare(
            request, desired, target,
            rawSqlBaseline = RawSqlBaseline(observedBeforeRun = observed, touchedObjects = emptySet()),
        ).shouldBeInstanceOf<PostCompareOutcome.Drift>()
    }
})
