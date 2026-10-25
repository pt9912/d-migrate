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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Der Abdruck einer Domain-Spalte auf SQLite loest die Domain schon ueber den schema-bewussten Kanonisierer auf den
 * Basistyp auf; die Aenderung an `TableComparator`/`TargetProjection` (Issue #56) beruehrt ihn nicht. Gepinnt wird das
 * ueber die echte Verdrahtung (dieselben Argumente wie `SchemaMigrateRunner.endpointFingerprints`) und ueber
 * Gleichheits-Aussagen, nicht ueber feste Hashes -- die enthalten die Algorithmus-Version.
 */
class SqliteDomainFingerprintWiringTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.loadAll() }
    afterSpec { DatabaseDriverRegistry.clear() }

    val dialect = DatabaseDialect.SQLITE

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

    /** Wie `SchemaMigrateRunner.endpointFingerprints` den Abdruck einer Seite bildet. */
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

    fun domain(base: String, precision: Int? = null, scale: Int? = null) = "d" to CustomTypeDefinition(
        kind = CustomTypeKind.DOMAIN, baseType = base, precision = precision, scale = scale,
    )

    val ref = NeutralType.Enum(refType = "d")

    test("Domain ueber boolean hat den Abdruck der Spalte mit direktem Basistyp") {
        fingerprint(schema(ref, domain("boolean"))) shouldBe fingerprint(schema(NeutralType.BooleanType))
    }

    test("Domain ueber decimal hat den Abdruck der Spalte mit direktem Basistyp") {
        fingerprint(schema(ref, domain("decimal", 10, 2))) shouldBe fingerprint(schema(NeutralType.Decimal(10, 2)))
    }

    test("Domain ueber integer hat den Abdruck der Reverse-Form Integer") {
        fingerprint(schema(ref, domain("integer"))) shouldBe fingerprint(schema(NeutralType.Integer))
    }

    test("ein echter Basistypwechsel aendert den Abdruck") {
        fingerprint(schema(ref, domain("boolean"))) shouldNotBe fingerprint(schema(ref, domain("text")))
    }
})
