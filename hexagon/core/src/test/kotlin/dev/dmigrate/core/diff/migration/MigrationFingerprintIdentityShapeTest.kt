package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Die beiden Schreibweisen einer Autowert-Spalte im Abdruck.
 *
 * `identifier` + `auto_increment` schreibt ein Soll von Hand; der numerische
 * Typ mit `generation: identity` ist, was der Reverse liefert. Wo der Dialekt
 * beide zum selben DDL rendert, sind sie dieselbe Spalte — und der Abdruck
 * muss das genauso sehen wie der Comparator, sonst meldet der Post-Compare
 * Drift auf einer Spalte, die genau wie gewuenscht angewendet wurde.
 */
class MigrationFingerprintIdentityShapeTest : FunSpec({

    /**
     * Was ein Dialekt mit Identity-Faltung aus dem Typ macht: Oracle rendert
     * `identifier` als `NUMBER(9)` und liest es als `integer` zurueck, die
     * `auto_increment`-Angabe ist danach nur noch in `generation` zu finden.
     */
    val foldingTypeCanonicalizer: (NeutralType) -> NeutralType = { type ->
        if (type is NeutralType.Identifier) NeutralType.Integer else type
    }

    fun schemaWith(column: ColumnDefinition) = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "t" to TableDefinition(columns = linkedMapOf("id" to column), primaryKey = listOf("id")),
        ),
    )

    val handWritten = schemaWith(ColumnDefinition(NeutralType.Identifier(autoIncrement = true)))
    val reverseRead = schemaWith(
        ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS)),
    )

    fun fingerprint(schema: SchemaDefinition, folds: Boolean) = MigrationFingerprint.compute(
        schema,
        canonicalizeType = foldingTypeCanonicalizer,
        foldsAutoIncrementOntoIdentity = folds,
    )

    test("with the fold both spellings hash the same") {
        fingerprint(handWritten, folds = true) shouldBe fingerprint(reverseRead, folds = true)
    }

    test("without the fold they do not — the difference is real for a dialect that spells them apart") {
        // PostgreSQL rendert `auto_increment` als SERIAL, nicht als IDENTITY;
        // dort waere die Gleichsetzung falsch.
        fingerprint(handWritten, folds = false) shouldNotBe fingerprint(reverseRead, folds = false)
    }

    test("the fold only fills an ABSENT generation — a declared mode stays as declared") {
        val byDefault = schemaWith(
            ColumnDefinition(
                NeutralType.Identifier(autoIncrement = true),
                generation = ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT),
            ),
        )
        fingerprint(byDefault, folds = true) shouldNotBe fingerprint(handWritten, folds = true)
    }

    test("a column that is neither spelling is untouched by the fold") {
        val plain = schemaWith(ColumnDefinition(NeutralType.Integer))
        fingerprint(plain, folds = true) shouldBe fingerprint(plain, folds = false)
    }
})
