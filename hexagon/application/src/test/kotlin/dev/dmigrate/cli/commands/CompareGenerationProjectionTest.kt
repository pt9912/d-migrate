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

    context("legacy_serial_syntax bleibt ein Unterschied — die Mehrdeutigkeit loest der Reverse") {

        // Ob MySQLs `AUTO_INCREMENT` eine `SERIAL`- oder eine IDENTITY-Spalte
        // meint, steht nicht in der Datenbank. Das entscheidet eine
        // deklarierte Reverse-Praeferenz (spec/dialect-preference-mechanism.md),
        // nie der Vergleich: ohne sie liest der Reverse `serial`, mit ihr kein Flag.
        val pgIdentity = ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT, sequenceName = "public.orders_id_seq")
        val mysqlAsIdentity = ColumnGeneration.Identity()

        test("a PostgreSQL IDENTITY against MySQL's AUTO_INCREMENT read as serial is a change") {
            val change = compare(side(pgIdentity, DatabaseDialect.POSTGRESQL), side(mysql, DatabaseDialect.MYSQL))
                .tablesChanged.single().columnsChanged.single().generation!!
            (change.before as ColumnGeneration.Identity).legacySerialSyntax shouldBe false
            (change.after as ColumnGeneration.Identity).legacySerialSyntax shouldBe true
        }

        test("… and no change once the MySQL reverse declared identity") {
            compare(side(pgIdentity, DatabaseDialect.POSTGRESQL), side(mysqlAsIdentity, DatabaseDialect.MYSQL))
                .tablesChanged.shouldBeEmpty()
        }

        test("no dialect folds the flag, not even one whose reverse never sets it") {
            for (dialect in DatabaseDialect.entries) {
                withClue(dialect) {
                    compare(side(pgIdentity, DatabaseDialect.POSTGRESQL), side(mysql, dialect))
                        .tablesChanged.shouldNotBeEmpty()
                }
            }
        }

        test("the mode stays a change even with the preference declared") {
            compare(
                side(pgIdentity.copy(mode = IdentityMode.ALWAYS), DatabaseDialect.POSTGRESQL),
                side(mysqlAsIdentity, DatabaseDialect.MYSQL),
            ).tablesChanged.single().columnsChanged.single().generation shouldNotBe null
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
