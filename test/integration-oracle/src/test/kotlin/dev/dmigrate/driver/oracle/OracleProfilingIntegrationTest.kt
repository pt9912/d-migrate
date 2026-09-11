package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.oracle.profiling.OracleLogicalTypeResolver
import dev.dmigrate.driver.oracle.profiling.OracleProfilingDataAdapter
import dev.dmigrate.driver.oracle.profiling.OracleSchemaIntrospectionAdapter
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.service.ProfileDatabaseService
import dev.dmigrate.profiling.service.ProfileTableService
import dev.dmigrate.profiling.types.TargetLogicalType
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * `data profile` gegen ein ECHTES Oracle.
 *
 * Der Schwerpunkt liegt auf den drei Stellen, an denen Oracle sich anders
 * verhaelt als die vier anderen Dialekte: ein Leerstring **ist** NULL,
 * `COUNT(DISTINCT clob)` scheitert mit ORA-22849, und Typvertraeglichkeit
 * beantwortet `VALIDATE_CONVERSION` statt eines Cast-und-Fangen. Die
 * Unit-Tests koennen nur bestaetigen, was beim Schreiben angenommen wurde —
 * hier laeuft es gegen den Server.
 */
class OracleProfilingIntegrationTest : FunSpec({

    val container = OracleContainer(TestImages.ORACLE)
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var config: ConnectionConfig

    val adapters = ProfilingAdapterSet(
        OracleSchemaIntrospectionAdapter(),
        OracleProfilingDataAdapter(),
        OracleLogicalTypeResolver(),
    )

    beforeSpec {
        container.start()
        config = ConnectionConfig(
            dialect = DatabaseDialect.ORACLE,
            host = container.host,
            port = container.oraclePort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
        )
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """CREATE TABLE "facts" (
                             "id" NUMBER(9) PRIMARY KEY,
                             "nm" VARCHAR2(50),
                             "body" CLOB,
                             "amt" NUMBER(10,2),
                             "ts" TIMESTAMP,
                             "code" VARCHAR2(20) CONSTRAINT "uq_facts_code" UNIQUE
                           )""",
                    )
                    // Zeile 2 traegt einen Leerstring, Zeile 3 nur Leerraum.
                    stmt.execute("""INSERT INTO "facts" VALUES (1, 'a', 'text', 10.5, SYSTIMESTAMP, 'c1')""")
                    stmt.execute("""INSERT INTO "facts" VALUES (2, '', 'text', 20, SYSTIMESTAMP, 'c2')""")
                    stmt.execute("""INSERT INTO "facts" VALUES (3, '  ', NULL, NULL, NULL, 'c3')""")
                    stmt.execute("""INSERT INTO "facts" VALUES (4, 'a', 'other', 10.5, SYSTIMESTAMP, 'c4')""")
                    stmt.execute("COMMIT")
                    // Die Typen, an denen ein Profillauf sonst mit einem
                    // ORA-Code abbricht statt zu beschreiben.
                    stmt.execute(
                        """CREATE TABLE "odd" (
                             "id" NUMBER(9) PRIMARY KEY,
                             "x" XMLTYPE,
                             "j" JSON,
                             "b" BLOB,
                             "r" RAW(16),
                             "d" DATE,
                             "tz" TIMESTAMP(6) WITH TIME ZONE
                           )""",
                    )
                    stmt.execute(
                        """INSERT INTO "odd" VALUES (1, XMLTYPE('<a>1</a>'), JSON('{"k":1}'),
                             HEXTORAW('AABB'), HEXTORAW('CC'), DATE '2024-01-01', SYSTIMESTAMP)""",
                    )
                    stmt.execute("COMMIT")
                }
            }
        }
    }

    afterSpec { container.stop() }

    test("an empty string is reported as null, not as an empty string") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val metrics = adapters.data.columnMetrics(pool, "facts", "nm", "VARCHAR2")
            // Vier Zeilen, eine davon mit '' -- die zaehlt in Oracle als NULL.
            withClue("nonNull=${metrics.nonNullCount} null=${metrics.nullCount}") {
                metrics.nonNullCount shouldBe 3L
                metrics.nullCount shouldBe 1L
            }
            // Es gibt keine leere Zeichenkette zu zaehlen.
            metrics.emptyStringCount shouldBe 0L
            // '  ' ist dagegen nicht NULL und wird als Leerraum erkannt.
            metrics.blankStringCount shouldBe 1L
        }
    }

    test("a CLOB column profiles instead of failing on COUNT(DISTINCT)") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            // Ohne die Projektion scheitert das hier mit ORA-22849.
            val metrics = adapters.data.columnMetrics(pool, "facts", "body", "CLOB")
            metrics.nonNullCount shouldBe 3L
            metrics.distinctCount shouldBe 2L
            metrics.maxLength.shouldNotBeNull()
            adapters.data.topValues(pool, "facts", "body").map { it.value } shouldContain "text"
        }
    }

    test("numeric and temporal statistics come back, the timestamp in ISO form") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val numeric = adapters.data.numericStats(pool, "facts", "amt").shouldNotBeNull()
            numeric.min shouldBe 10.5
            numeric.max shouldBe 20.0
            numeric.stddev.shouldNotBeNull()
            val temporal = adapters.data.temporalStats(pool, "facts", "ts").shouldNotBeNull()
            // Die Maske steht ausgeschrieben; ohne sie entschiede NLS_DATE_FORMAT.
            withClue("min=${temporal.minTimestamp}") {
                // Ein TIMESTAMP traegt Bruchteilsekunden -- ohne sie faenden
                // zwei verschiedene Zeitpunkte denselben Text.
                temporal.minTimestamp!!.matches(
                    Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{9}"""),
                ) shouldBe true
            }
        }
    }

    test("type compatibility is answered by the server, with the offending values named") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val result = adapters.data
                .targetTypeCompatibility(pool, "facts", "nm", listOf(TargetLogicalType.INTEGER))
                .single()
            // 'a', '  ' und 'a' sind keine Zahlen.
            result.checkedValueCount shouldBe 3L
            result.compatibleCount shouldBe 0L
            result.exampleInvalidValues shouldContain "a"
        }
    }

    test("keys and unique columns come from the constraint catalog") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val columns = adapters.introspection.listColumns(pool, "facts").associateBy { it.name }
            columns.getValue("id").isPrimaryKey shouldBe true
            columns.getValue("code").isUnique shouldBe true
            // Der Primaerschluessel zaehlt nicht zusaetzlich als unique.
            columns.getValue("id").isUnique shouldBe false
            columns.getValue("nm").nullable shouldBe true
        }
    }

    test("the whole profile runs end to end through the service") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val profile = ProfileDatabaseService(adapters, ProfileTableService(adapters))
                .profile(pool = pool, databaseProduct = "oracle", schema = null)
            profile.tables.map { it.name } shouldContain "facts"
        }
    }

    test("XML, JSON, LOB, binary and both temporal types profile without failing") {
        // Ohne die VARCHAR2-Projektion scheitert XMLTYPE mit ORA-22849; ohne
        // typabhaengige Zeitmaske ist `.FF` an einem DATE ORA-01821.
        HikariConnectionPoolFactory.create(config).use { pool ->
            val profile = ProfileDatabaseService(adapters, ProfileTableService(adapters))
                .profile(pool = pool, databaseProduct = "oracle", schema = null)
            val odd = profile.tables.single { it.name == "odd" }
            withClue("Spalten: ${odd.columns.map { it.name }}") {
                odd.columns.map { it.name } shouldContain "x"
                odd.columns.map { it.name } shouldContain "j"
                odd.columns.map { it.name } shouldContain "tz"
            }
        }
    }

    test("a LONG column is named rather than answered with an ORA code") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("""CREATE TABLE "legacy" ("id" NUMBER(9), "note" LONG)""")
                    stmt.execute("""INSERT INTO "legacy" VALUES (1, 'text')""")
                    stmt.execute("COMMIT")
                }
            }
            // COUNT(long) waere ORA-00997 -- der Betreiber saehe nur den Code.
            val failure = shouldThrow<Throwable> {
                adapters.data.columnMetrics(pool, "legacy", "note", "LONG")
            }
            withClue("Fehler: ${failure.message}") {
                failure.message!! shouldContain "cannot be profiled"
            }
        }
    }

    test("a timestamp with a fractional second keeps both values apart") {
        // Ohne Bruchteilsekunden in der Maske faenden zwei verschiedene
        // Zeitpunkte denselben Text, und topValues zeigte ihn zweimal.
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("""CREATE TABLE "fractions" ("ts" TIMESTAMP(6))""")
                    stmt.execute("""INSERT INTO "fractions" VALUES (TIMESTAMP '2024-01-01 10:00:00.100000')""")
                    stmt.execute("""INSERT INTO "fractions" VALUES (TIMESTAMP '2024-01-01 10:00:00.200000')""")
                    stmt.execute("COMMIT")
                }
            }
            val values = adapters.data.topValues(pool, "fractions", "ts").map { it.value }
            withClue("Werte: $values") { values.distinct().size shouldBe 2 }
        }
    }
})
