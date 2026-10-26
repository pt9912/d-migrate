package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Der Abdruck einer Domain-Spalte auf PostgreSQL (Issue #68): beide Seiten falten die Domain-Spalte auf den
 * Basistyp, **schemagebunden** (jede Seite gegen ihre eigenen Custom Types). Die Datenbank-Seite, die der Reader
 * jetzt als `Enum(refType)` liefert, hasht damit wie die Basistyp-Spalte, die sie vorher war; der Algorithmus-Tag
 * bleibt, kein bestehender Datenbank-Abdruck aendert sich. Gepinnt ueber Gleichheits-Aussagen derselben Verdrahtung
 * wie `SchemaMigrateRunner.endpointFingerprints`, nicht ueber feste Hashes.
 */
class PostgresDomainFingerprintWiringTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.loadAll() }
    afterSpec { DatabaseDriverRegistry.clear() }

    val dialect = DatabaseDialect.POSTGRESQL

    fun schema(type: NeutralType, vararg customTypes: Pair<String, CustomTypeDefinition>) = SchemaDefinition(
        name = "s", version = "1", customTypes = mapOf(*customTypes),
        tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "n" to ColumnDefinition(type),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    /** Wie `SchemaMigrateRunner.endpointFingerprints` und `SchemaRollbackRunner` den Abdruck einer Seite bilden. */
    fun fingerprint(schema: SchemaDefinition): String = MigrationFingerprint.compute(
        schema,
        registrySchemaAwareCanonicalizer(dialect, schema),
        capabilityIndexCanonicalizer(dialect),
        capabilityGenerationCanonicalizer(dialect, null),
        capabilityPartitionCanonicalizer(dialect),
        capabilityFoldsAutoIncrementOntoIdentity(dialect),
        capabilityProjectsCustomTypes(dialect),
        capabilityResolvesRefTypeVocabulary(dialect),
    )

    fun domain(base: String, precision: Int? = null, scale: Int? = null) = "dd" to CustomTypeDefinition(
        kind = CustomTypeKind.DOMAIN, baseType = base, precision = precision, scale = scale,
    )

    val ref = NeutralType.Enum(refType = "dd")

    val matrix = listOf(
        Triple("integer", null, NeutralType.Integer),
        Triple("biginteger", null, NeutralType.BigInteger),
        Triple("text", null, NeutralType.Text()),
        Triple("varchar(50)", null, NeutralType.Text(50)),
        Triple("decimal", 10 to 2, NeutralType.Decimal(10, 2)),
        Triple("uuid", null, NeutralType.Uuid),
        Triple("boolean", null, NeutralType.BooleanType),
    )

    test("die Domain-Spalte hasht wie die Basistyp-Spalte neben derselben Domain, fuer jeden Basistyp") {
        for ((base, ps, type) in matrix) {
            withClue(base) {
                val custom = domain(base, ps?.first, ps?.second)
                // Datenbank-Seite (Reader: Enum(refType)) = Altdatei (Basistyp) = Datei mit ref_type.
                fingerprint(schema(ref, custom)) shouldBe fingerprint(schema(type, custom))
            }
        }
    }

    test("die Domain-Spalte hasht nicht wie eine Spalte ohne die Domain (Domain als Typ bleibt im Abdruck)") {
        fingerprint(schema(ref, domain("integer"))) shouldNotBe fingerprint(schema(NeutralType.Integer))
    }

    test("der Abdruck unterscheidet weiter echte Aenderungen der Domain-Spalte") {
        fingerprint(schema(ref, domain("integer"))) shouldNotBe fingerprint(schema(ref, domain("text")))
        fingerprint(schema(ref, domain("integer"))) shouldNotBe fingerprint(schema(NeutralType.Text(), domain("integer")))
    }

    test("kontextfrei bleibt Enum(refType) stehen (der Rollback bindet deshalb das gelesene Schema)") {
        val contextFree = registryTypeCanonicalizer(dialect)
        contextFree(ref) shouldBe ref
        registrySchemaAwareCanonicalizer(dialect, schema(ref, domain("integer")))(ref) shouldBe NeutralType.Integer
    }

    test("der Abdruck einer Enum-Spalte bleibt bei Enum(refType): zwei Enums mit denselben Werten sind zwei Typen") {
        val enumA = "dd" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "b"))
        val enumB = "ee" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "b"))
        fingerprint(schema(ref, enumA, enumB)) shouldNotBe
            fingerprint(schema(NeutralType.Enum(refType = "ee"), enumA, enumB))
    }

    // ── Die zwei dokumentierten Blindheiten des gefalteten Abdrucks (beide fing der Plan schon vorher) ──
    //
    // Falten beide Seiten die Domain-Spalte auf den Basistyp, sieht der Abdruck weder den Wechsel zwischen zwei
    // Domains gleichen Basistyps noch den Wechsel von der Basistyp-Spalte zur Domain-Spalte. Der Plan faengt beide:
    // der strukturelle Vergleich meldet ein AlterColumnType (Exit 8), bevor ein Post-Compare laeuft. Blind ist nur
    // der Post-Compare nach einem erfolgreichen Lauf, in dem eine Operation das Ziel ohne den Wechsel erreicht haette.

    test("Blindheit 1: zwei Domains gleichen Basistyps an derselben Spalte hashen gleich (der Plan sieht den Wechsel)") {
        val both = arrayOf(domain("integer"), "ee" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "integer"))
        fingerprint(schema(ref, *both)) shouldBe fingerprint(schema(NeutralType.Enum(refType = "ee"), *both))
    }

    test("Blindheit 2: Basistyp-Spalte gegen Domain-Spalte neben derselben Domain hasht gleich (der Plan sieht den Wechsel)") {
        fingerprint(schema(NeutralType.Integer, domain("integer"))) shouldBe fingerprint(schema(ref, domain("integer")))
    }
})
