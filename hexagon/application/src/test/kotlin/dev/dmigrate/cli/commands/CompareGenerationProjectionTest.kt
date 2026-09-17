package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.PostgresServerVersion
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Der Identity-Sequenzname im **symmetrischen** Vergleich (`schema compare`).
 *
 * Der Konsument meldete `Identity(mode=BY_DEFAULT, sequenceName=public.customer_id_seq)`
 * gegen `sequenceName=null` fuer PostgreSQL gegen MySQL. Der Name stammt aus
 * dem PostgreSQL-Reverse; MySQL setzt bei `AUTO_INCREMENT` nie einen. Er
 * beschreibt, wie der Server die Erzeugung organisiert, nicht, was die Spalte
 * ist — dieselbe Faehigkeits-Naht, die `schema migrate` schon benutzt, aber
 * nur ihr Namens-Teil.
 */
class CompareGenerationProjectionTest : FunSpec({

    fun schemaWith(generation: ColumnGeneration?) = SchemaDefinition(
        name = "app", version = "1",
        tables = mapOf(
            "customer" to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(type = NeutralType.BigInteger, generation = generation)),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun side(generation: ColumnGeneration?, dialect: DatabaseDialect?) = CompareSide(schemaWith(generation), dialect)

    /** `schema compare`, wie CLI und MCP ihn bauen. */
    fun compare(source: CompareSide, target: CompareSide) = SchemaComparator(
        canonicalizeRawExpressions = true,
        comparisonGeneration = compareGenerationCanonicalizer(source, target),
    ).compare(source.schema, target.schema)

    val postgres = ColumnGeneration.Identity(
        mode = IdentityMode.BY_DEFAULT, sequenceName = "public.customer_id_seq", legacySerialSyntax = true,
    )
    val mysql = ColumnGeneration.Identity(legacySerialSyntax = true)
    val oracle = ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT, sequenceName = "ISEQ\$\$_73345")

    context("Welcher Dialekt uebergeben wird") {

        test("the first side whose reverse keeps the name as server bookkeeping") {
            compareProjectionDialect(DatabaseDialect.POSTGRESQL, DatabaseDialect.MYSQL) shouldBe DatabaseDialect.POSTGRESQL
            compareProjectionDialect(DatabaseDialect.MYSQL, DatabaseDialect.ORACLE) shouldBe DatabaseDialect.ORACLE
            compareProjectionDialect(DatabaseDialect.POSTGRESQL, DatabaseDialect.ORACLE) shouldBe DatabaseDialect.POSTGRESQL
            compareProjectionDialect(null, DatabaseDialect.POSTGRESQL) shouldBe DatabaseDialect.POSTGRESQL
        }

        test("none, when no side reads the name as bookkeeping — the comparison stays strict") {
            compareProjectionDialect(DatabaseDialect.MYSQL, DatabaseDialect.MSSQL).shouldBeNull()
            compareProjectionDialect(DatabaseDialect.SQLITE, null).shouldBeNull()
            compareProjectionDialect(null, null).shouldBeNull()
            compareGenerationCanonicalizer(side(mysql, null), side(mysql, null)).shouldBeNull()
        }
    }

    context("Die drei Paarungen des DoD") {

        test("PostgreSQL against MySQL no longer reports the identity") {
            compare(side(postgres, DatabaseDialect.POSTGRESQL), side(mysql, DatabaseDialect.MYSQL))
                .tablesChanged.shouldBeEmpty()
            compare(side(mysql, DatabaseDialect.MYSQL), side(postgres, DatabaseDialect.POSTGRESQL))
                .tablesChanged.shouldBeEmpty()
        }

        test("MySQL against MySQL with a different mode still reports it") {
            val always = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS, legacySerialSyntax = true)
            compare(side(mysql, DatabaseDialect.MYSQL), side(always, DatabaseDialect.MYSQL))
                .tablesChanged.single().columnsChanged.single().generation shouldNotBe null
        }

        test("PostgreSQL against Oracle folds both names: both were assigned by a server") {
            compare(side(postgres.copy(legacySerialSyntax = false), DatabaseDialect.POSTGRESQL), side(oracle, DatabaseDialect.ORACLE))
                .tablesChanged.shouldBeEmpty()
        }

        test("a different mode is still a change across dialects") {
            val always = oracle.copy(mode = IdentityMode.ALWAYS)
            compare(side(postgres.copy(legacySerialSyntax = false), DatabaseDialect.POSTGRESQL), side(always, DatabaseDialect.ORACLE))
                .tablesChanged.shouldNotBeEmpty()
        }

        test("two hand-written schemas keep comparing the name") {
            compare(side(postgres, null), side(mysql, null)).tablesChanged.shouldNotBeEmpty()
        }

        test("the reported change still carries the unprojected names") {
            val change = compare(
                side(postgres, DatabaseDialect.POSTGRESQL),
                side(mysql.copy(mode = IdentityMode.ALWAYS), DatabaseDialect.MYSQL),
            ).tablesChanged.single().columnsChanged.single().generation!!
            (change.before as ColumnGeneration.Identity).sequenceName shouldBe "public.customer_id_seq"
        }
    }

    context("P10: legacy_serial_syntax zaehlt nur, wo beide Seiten SERIAL und IDENTITY unterscheiden") {

        val pgIdentity = ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT, sequenceName = "public.orders_id_seq")

        test("only PostgreSQL's reverse tells the two apart (capability per dialect)") {
            DialectCapabilities.forDialect(DatabaseDialect.POSTGRESQL).distinguishesSerialFromIdentity shouldBe true
            for (dialect in listOf(DatabaseDialect.MYSQL, DatabaseDialect.SQLITE, DatabaseDialect.MSSQL, DatabaseDialect.ORACLE)) {
                withClue(dialect) { DialectCapabilities.forDialect(dialect).distinguishesSerialFromIdentity shouldBe false }
            }
        }

        test("the first side whose dialect does not tell them apart") {
            compareSerialDialect(DatabaseDialect.POSTGRESQL, DatabaseDialect.MYSQL) shouldBe DatabaseDialect.MYSQL
            compareSerialDialect(DatabaseDialect.MSSQL, DatabaseDialect.POSTGRESQL) shouldBe DatabaseDialect.MSSQL
            compareSerialDialect(DatabaseDialect.SQLITE, DatabaseDialect.ORACLE) shouldBe DatabaseDialect.SQLITE
            compareSerialDialect(DatabaseDialect.POSTGRESQL, DatabaseDialect.POSTGRESQL).shouldBeNull()
            compareSerialDialect(DatabaseDialect.POSTGRESQL, null).shouldBeNull()
            compareSerialDialect(null, null).shouldBeNull()
        }

        test("a PostgreSQL IDENTITY against MySQL's AUTO_INCREMENT is no change") {
            compare(side(pgIdentity, DatabaseDialect.POSTGRESQL), side(mysql, DatabaseDialect.MYSQL))
                .tablesChanged.shouldBeEmpty()
            compare(side(mysql, DatabaseDialect.MYSQL), side(pgIdentity, DatabaseDialect.POSTGRESQL))
                .tablesChanged.shouldBeEmpty()
        }

        test("against SQL Server and SQLite the flag does not count either") {
            val mssql = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS)
            compare(side(postgres.copy(mode = IdentityMode.ALWAYS), DatabaseDialect.POSTGRESQL), side(mssql, DatabaseDialect.MSSQL))
                .tablesChanged.shouldBeEmpty()
            compare(side(pgIdentity, DatabaseDialect.POSTGRESQL), side(mysql, DatabaseDialect.SQLITE))
                .tablesChanged.shouldBeEmpty()
        }

        test("the mode stays a change: SQL Server reads BY DEFAULT as ALWAYS (W140)") {
            val mssql = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS)
            compare(side(pgIdentity, DatabaseDialect.POSTGRESQL), side(mssql, DatabaseDialect.MSSQL))
                .tablesChanged.single().columnsChanged.single().generation shouldNotBe null
        }

        test("two PostgreSQL reverses: SERIAL against IDENTITY stays a change") {
            compare(side(postgres, DatabaseDialect.POSTGRESQL), side(pgIdentity, DatabaseDialect.POSTGRESQL))
                .tablesChanged.shouldNotBeEmpty()
        }

        test("a PostgreSQL reverse against a hand-written schema, and two hand-written ones, stay strict") {
            compare(side(pgIdentity, DatabaseDialect.POSTGRESQL), side(pgIdentity.copy(legacySerialSyntax = true), null))
                .tablesChanged.shouldNotBeEmpty()
            compare(side(pgIdentity.copy(sequenceName = null), null), side(mysql, null))
                .tablesChanged.shouldNotBeEmpty()
        }

        test("a MySQL reverse against a hand-written schema does not count the flag") {
            compare(side(mysql, DatabaseDialect.MYSQL), side(ColumnGeneration.Identity(), null))
                .tablesChanged.shouldBeEmpty()
        }

        test("the reported change still carries the unprojected flag") {
            val change = compare(
                side(pgIdentity, DatabaseDialect.POSTGRESQL),
                side(mysql.copy(mode = IdentityMode.ALWAYS), DatabaseDialect.MYSQL),
            ).tablesChanged.single().columnsChanged.single().generation!!
            (change.after as ColumnGeneration.Identity).legacySerialSyntax shouldBe true
        }

        test("migrate's seam, the strict comparator and the fingerprint keep the flag") {
            capabilityGenerationCanonicalizer(DatabaseDialect.MYSQL)(mysql) shouldBe mysql
            capabilityGenerationCanonicalizer(DatabaseDialect.MSSQL)(mysql) shouldBe mysql
            SchemaComparator().compare(schemaWith(pgIdentity.copy(sequenceName = null)), schemaWith(mysql))
                .tablesChanged.shouldNotBeEmpty()
            MigrationFingerprint.compute(schemaWith(ColumnGeneration.Identity())) shouldNotBe
                MigrationFingerprint.compute(schemaWith(mysql))
        }
    }

    context("Nur der Namens-Teil der Naht") {

        test("`stored` stays visible even where PostgreSQL has no virtual form") {
            val virtual = ColumnGeneration.Computed(expression = "a + b", stored = false)
            val stored = virtual.copy(stored = true)
            compare(side(virtual, DatabaseDialect.POSTGRESQL), side(stored, DatabaseDialect.MYSQL))
                .tablesChanged.shouldNotBeEmpty()
            // Gegenprobe: die ganze Naht faltet es fuer PostgreSQL 17.
            capabilityGenerationCanonicalizer(DatabaseDialect.POSTGRESQL, PostgresServerVersion(17, 0))(virtual) shouldBe stored
        }

        test("the whole seam still folds both parts for the migrate path") {
            val pg17 = capabilityGenerationCanonicalizer(DatabaseDialect.POSTGRESQL, PostgresServerVersion(17, 0))
            (pg17(postgres) as ColumnGeneration.Identity).sequenceName.shouldBeNull()
            val pg18 = capabilityGenerationCanonicalizer(DatabaseDialect.POSTGRESQL, PostgresServerVersion(18, 6))
            val virtual = ColumnGeneration.Computed(expression = "a + b", stored = false)
            pg18(virtual) shouldBe virtual
            capabilityGenerationCanonicalizer(DatabaseDialect.MYSQL)(virtual) shouldBe virtual
        }
    }

    context("Migrate-Pfad und Fingerabdruck bleiben unberuehrt") {

        test("migrate's comparator without the projection still reports the name") {
            SchemaComparator().compare(schemaWith(postgres), schemaWith(mysql)).tablesChanged.shouldNotBeEmpty()
        }

        test("the fingerprint without a generation hook keeps the two apart") {
            MigrationFingerprint.compute(schemaWith(postgres)) shouldNotBe MigrationFingerprint.compute(schemaWith(mysql))
        }
    }

    context("Der Dialekt einer Seite kommt aus der Reverse-Markierung") {

        fun reversed(name: String) = SchemaDefinition(name = name, version = ReverseScopeCodec.REVERSE_VERSION)

        test("each reader's marker names its dialect") {
            reverseSourceDialect(reversed(ReverseScopeCodec.postgresName("db", "public"))) shouldBe DatabaseDialect.POSTGRESQL
            reverseSourceDialect(reversed(ReverseScopeCodec.mysqlName("db"))) shouldBe DatabaseDialect.MYSQL
            reverseSourceDialect(reversed(ReverseScopeCodec.sqliteName("main"))) shouldBe DatabaseDialect.SQLITE
            reverseSourceDialect(reversed(ReverseScopeCodec.mssqlName("db", "dbo"))) shouldBe DatabaseDialect.MSSQL
            reverseSourceDialect(reversed(ReverseScopeCodec.oracleName("APP"))) shouldBe DatabaseDialect.ORACLE
        }

        test("a hand-written schema, a half marker or an unknown dialect has none") {
            reverseSourceDialect(SchemaDefinition(name = "app", version = "1")).shouldBeNull()
            reverseSourceDialect(SchemaDefinition(name = ReverseScopeCodec.mysqlName("db"), version = "1")).shouldBeNull()
            reverseSourceDialect(reversed("${ReverseScopeCodec.PREFIX}db2:database=x")).shouldBeNull()
        }
    }
})
