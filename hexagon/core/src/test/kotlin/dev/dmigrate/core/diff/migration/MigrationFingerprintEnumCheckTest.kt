package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * v8: der Wertevorrat eines Enums zaehlt gleich, egal in welcher Darstellung
 * er vorliegt — am Spaltentyp (authored) oder als eigener `IN`-CHECK
 * (zurueckgelesen aus einem Dialekt ohne Enum-Typ).
 *
 * Siehe `docs/planning/done/fingerprint-v8-enum-check-projection.md`.
 */
class MigrationFingerprintEnumCheckTest : FunSpec({

    fun schema(tables: Map<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables)

    test("an authored enum fingerprints identically to a reverse-read text column with its IN check") {
        // Genau der Fall, der jede MSSQL-Migration mit Enum-Spalte als
        // driftend melden wuerde: authored fuehrt den Wertevorrat am Typ,
        // zurueckgelesen steht er als eigener CHECK daneben.
        val authored = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("mood" to ColumnDefinition(NeutralType.Enum(values = listOf("red", "green")))),
        )))
        val reversed = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("mood" to ColumnDefinition(NeutralType.Text(5))),
            constraints = listOf(
                ConstraintDefinition(
                    name = "ck_t_mood",
                    type = ConstraintType.CHECK,
                    expression = "mood IN ('red', 'green')",
                ),
            ),
        )))
        // Die Typseite faltet der Kanonisierer (enum -> text), die
        // Constraint-Kante diese Projektion.
        val toText: (NeutralType) -> NeutralType = { if (it is NeutralType.Enum) NeutralType.Text(5) else it }
        MigrationFingerprint.compute(authored, toText) shouldBe MigrationFingerprint.compute(reversed, toText)
    }

    test("the check's literal order does not matter for the match") {
        // Der Wertevorrat selbst ist geordnet — MySQLs nativer ENUM hat
        // Ordinal-Semantik, die Reihenfolge im TYP ist also bedeutsam und wird
        // nicht wegsortiert. Fuer den Abgleich der beiden DARSTELLUNGEN darf
        // sie es nicht sein: welche Reihenfolge eine Datenbank in ihrem CHECK
        // zurueckliefert, ist nicht zugesichert.
        val authored = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("mood" to ColumnDefinition(NeutralType.Enum(values = listOf("red", "green")))),
        )))
        val reversedOtherOrder = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("mood" to ColumnDefinition(NeutralType.Text(5))),
            constraints = listOf(
                ConstraintDefinition(
                    name = "ck_t_mood",
                    type = ConstraintType.CHECK,
                    expression = "mood IN ('green', 'red')",
                ),
            ),
        )))
        val toText: (NeutralType) -> NeutralType = { if (it is NeutralType.Enum) NeutralType.Text(5) else it }
        MigrationFingerprint.compute(authored, toText) shouldBe
            MigrationFingerprint.compute(reversedOtherOrder, toText)
    }

    test("a target MISSING the check still differs — the fold does not hide real drift") {
        val authored = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("mood" to ColumnDefinition(NeutralType.Enum(values = listOf("red", "green")))),
        )))
        val withoutCheck = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("mood" to ColumnDefinition(NeutralType.Text(5))),
        )))
        val toText: (NeutralType) -> NeutralType = { if (it is NeutralType.Enum) NeutralType.Text(5) else it }
        MigrationFingerprint.compute(authored, toText) shouldNotBe
            MigrationFingerprint.compute(withoutCheck, toText)
    }

    test("a check whose values CONTRADICT the column's enum stays a constraint") {
        // Sonst verschwaende der Fold einen echten Unterschied.
        val contradicting = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("mood" to ColumnDefinition(NeutralType.Enum(values = listOf("red", "green")))),
            constraints = listOf(
                ConstraintDefinition(
                    name = "ck_other",
                    type = ConstraintType.CHECK,
                    expression = "mood IN ('blue')",
                ),
            ),
        )))
        MigrationFingerprint.project(contradicting) shouldContain "constraints[1]"
    }

    test("a check that is not an IN list over one of the table's columns stays a constraint") {
        val ranged = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("age" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(
                ConstraintDefinition(name = "ck_age", type = ConstraintType.CHECK, expression = "age > 0"),
            ),
        )))
        MigrationFingerprint.project(ranged) shouldContain "constraints[1]"
    }

    test("two matching checks on the same column fold NEITHER — one would vanish silently") {
        // Welcher von beiden den Wertevorrat beschreibt, ist nicht
        // entscheidbar. Einen zu falten liesse ihn spurlos verschwinden, samt
        // dem Unterschied, den er ausmacht.
        fun withChecks(vararg names: String) = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("status" to ColumnDefinition(NeutralType.Text(5))),
            constraints = names.map {
                ConstraintDefinition(name = it, type = ConstraintType.CHECK, expression = "status IN ('a', 'b')")
            },
        )))
        MigrationFingerprint.project(withChecks("chk_a", "chk_b")) shouldContain "constraints[2]"
        // Und eine Tabelle mit nur einem darf davon unterscheidbar bleiben.
        MigrationFingerprint.compute(withChecks("chk_a", "chk_b")) shouldNotBe
            MigrationFingerprint.compute(withChecks("chk_a"))
    }

    test("the constraint ORDER does not decide which check is folded") {
        fun inOrder(vararg exprs: String) = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("status" to ColumnDefinition(NeutralType.Text(5))),
            constraints = exprs.mapIndexed { i, e ->
                ConstraintDefinition(name = "chk_$i", type = ConstraintType.CHECK, expression = e)
            },
        )))
        // Zwei Treffer auf derselben Spalte: keiner faltet, egal wie herum.
        MigrationFingerprint.project(inOrder("status IN ('a')", "status IN ('a','b')")) shouldContain
            "enum_checks[0]"
        MigrationFingerprint.project(inOrder("status IN ('a','b')", "status IN ('a')")) shouldContain
            "enum_checks[0]"
    }

    test("values containing a comma cannot collide with two separate values") {
        // Ohne Escaping haetten `["a,b"]` und `["a","b"]` denselben Text.
        fun withValues(vararg v: String) = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("mood" to ColumnDefinition(NeutralType.Enum(values = v.toList()))),
        )))
        MigrationFingerprint.compute(withValues("a,b")) shouldNotBe
            MigrationFingerprint.compute(withValues("a", "b"))
    }
})
