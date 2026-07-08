package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * v7 (Typ-Kanonisierungs-Slice AP3): pins that [DiffPlanner.plan] threads
 * `canonicalizeType` into BOTH endpoint fingerprints — the runner computes
 * its own current/desired fingerprints with the same projection, and the
 * two paths must agree (`plan.current.fingerprint` feeds the rollback and
 * plan artefacts, the runner's value feeds overlay pins and F.5.e recovery).
 */
class DiffPlannerCanonicalizationTest : FunSpec({

    fun withType(t: NeutralType) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf("probe" to TableDefinition(columns = mapOf("val" to ColumnDefinition(t)))),
    )

    test("canonicalizeType flows into both endpoint fingerprints (runner parity)") {
        val current = withType(NeutralType.Integer)
        val desired = withType(NeutralType.SmallInt)
        val fold: (NeutralType) -> NeutralType =
            { if (it == NeutralType.SmallInt) NeutralType.Integer else it }
        val result = DiffPlanner().plan(current, desired, SchemaDiff(), canonicalizeType = fold)
        result.current.fingerprint shouldBe MigrationFingerprint.compute(current, fold)
        result.desired.fingerprint shouldBe MigrationFingerprint.compute(desired, fold)
        // Die Faltung macht beide Endpunkte identisch — exakt die Post-Compare-Semantik.
        result.current.fingerprint shouldBe result.desired.fingerprint
    }

    test("identity default keeps the endpoints dialekt-neutral distinct") {
        val result = DiffPlanner().plan(withType(NeutralType.Integer), withType(NeutralType.SmallInt), SchemaDiff())
        result.current.fingerprint shouldBe MigrationFingerprint.compute(withType(NeutralType.Integer))
        (result.current.fingerprint == result.desired.fingerprint) shouldBe false
    }
})
