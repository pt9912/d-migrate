package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Dieselbe Grenze wie bei der Index-Projektion: was ein Dialekt nicht
 * **vergeben** laesst, kann ein Anwender im Soll-Schema nicht hinschreiben,
 * der Reverse liest es aber trotzdem.
 *
 * Oracle vergibt den Sequenznamen einer IDENTITY-Spalte selbst. Live
 * gemessen (2026-09-06) ist er weder benennbar (`(SEQUENCE NAME …)` →
 * `ORA-02000`, `USING <seq>` → `ORA-03076`), noch stabil (dieselbe Tabelle
 * geloescht und identisch neu angelegt: `ISEQ${'$'}${'$'}_73345` →
 * `ISEQ${'$'}${'$'}_73349`), noch nachtraeglich aenderbar (`ORA-32799`).
 */
class CapabilityGenerationCanonicalizerTest : FunSpec({

    val identity = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS, sequenceName = "ISEQ\$\$_73345")

    fun schemaWith(generation: ColumnGeneration?) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), generation = generation),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    test("Oracle projects the system-generated sequence name away") {
        val projected = capabilityGenerationCanonicalizer(DatabaseDialect.ORACLE)(identity)
        projected shouldBe ColumnGeneration.Identity(mode = IdentityMode.ALWAYS, sequenceName = null)
    }

    test("Oracle keeps the identity mode -- only the name is unauthorable") {
        val byDefault = ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT, sequenceName = "ISEQ\$\$_1")
        val projected = capabilityGenerationCanonicalizer(DatabaseDialect.ORACLE)(byDefault)
        (projected as ColumnGeneration.Identity).mode shouldBe IdentityMode.BY_DEFAULT
        projected.sequenceName.shouldBeNull()
    }

    // Kein Gutbefund, sondern der heutige Stand: der PG-Renderer schreibt
    // den Namen ebenfalls nie, der PG-Reverse liest ihn aber. PG zu falten
    // aendert bestehende PG-Fingerabdruecke und damit die Gueltigkeit
    // erzeugter Rollback-Artefakte -- eigene Entscheidung, siehe
    // docs/planning/open/pg-identity-sequence-name-fingerprint.md.
    test("PostgreSQL keeps it today -- folding it there is a separate decision") {
        capabilityGenerationCanonicalizer(DatabaseDialect.POSTGRESQL)(identity) shouldBe identity
    }

    test("a column without generation stays null in every dialect") {
        for (dialect in DatabaseDialect.entries) {
            capabilityGenerationCanonicalizer(dialect)(null).shouldBeNull()
        }
    }

    /**
     * Der eigentliche Zweck: das user-authored Soll-Schema kann den Namen
     * nicht kennen (er entsteht erst beim `CREATE TABLE`), das
     * zurueckgelesene Ist-Schema traegt ihn. Ohne die Projektion meldete
     * der Post-Compare nach jedem `migrate --execute` Drift fuer eine
     * Spalte, die genau wie gewuenscht angelegt wurde.
     */
    test("desired without the name and observed with it hash equal for Oracle, unequal without the hook") {
        val desired = schemaWith(ColumnGeneration.Identity(mode = IdentityMode.ALWAYS, sequenceName = null))
        val observed = schemaWith(identity)
        val oracle = capabilityGenerationCanonicalizer(DatabaseDialect.ORACLE)

        MigrationFingerprint.compute(desired, canonicalizeGeneration = oracle) shouldBe
            MigrationFingerprint.compute(observed, canonicalizeGeneration = oracle)

        // Gegenprobe: ohne den Hook waeren es zwei verschiedene Abdruecke --
        // genau die falsche Drift-Meldung, die der Hook verhindert.
        MigrationFingerprint.compute(desired) shouldNotBe MigrationFingerprint.compute(observed)
    }
})
