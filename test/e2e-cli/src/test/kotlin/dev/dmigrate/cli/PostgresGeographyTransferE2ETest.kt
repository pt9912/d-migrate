package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import dev.dmigrate.test.containers.newMssqlContainer
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * P4 — der Datenpfad einer PostGIS-`geography`-Spalte, über die ECHTE CLI.
 *
 * Der Reader wrappte bis hierher nur `geometry` mit `ST_AsBinary`; eine
 * `geography`-Spalte kam als undurchsichtiges Objekt aus dem Treiber, und
 * die Import-Sitzung erkannte sie am Ziel nicht als Geometrie. Ein Transfer
 * aus so einer Spalte trug ihre Werte also nicht.
 *
 * Zwei Wege stehen hier nebeneinander:
 *
 * 1. **PostgreSQL → SQL Server.** Das Ziel wählt den Typ nach dem SRID
 *    (`spec/ddl-generation-rules.md`, Abschnitt 16.9): aus dem geodätischen
 *    4326 wird dort `geography`. Der SRID muss ankommen, sonst legte SQL
 *    Server planares `geometry` an.
 * 2. **In eine bestehende `geography`-Spalte schreiben.** Das Ziel ist hier
 *    PostgreSQL selbst, mit einer von Hand angelegten
 *    `geography(Point,4326)`-Spalte — der Fall, den die Import-Sitzung mit
 *    `ST_GeomFromWKB(?, srid)` bedienen muss (PostGIS erklärt den Weg von
 *    `geometry` nach `geography` als Zuweisungs-Cast).
 *
 * Was kein Adaptertest zeigen kann: dass Reverse, Generate, Runner und
 * Import-Sitzung über die Kommandogrenze zusammenpassen.
 *
 * EULA: `acceptLicense()` = `ACCEPT_EULA=Y` (docs/user/quality.md).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class PostgresGeographyTransferE2ETest : FunSpec({

    val source = PostgreSQLContainer(TestImages.POSTGIS)
        .withDatabaseName("dmigrate_geog_src")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    val target = newMssqlContainer()

    lateinit var tmp: Path

    fun mssqlPort() = target.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT)

    fun pgUrl(database: String): String =
        "postgresql://${source.username}:${source.password}@${source.host}:${source.firstMappedPort}/$database"

    fun mssqlUrl(): String =
        "mssql://${target.username}:${target.password}@${target.host}:${mssqlPort()}/geog_tgt?encrypt=false"

    fun pgExec(database: String, vararg sqls: String) =
        DriverManager.getConnection(
            "jdbc:postgresql://${source.host}:${source.firstMappedPort}/$database",
            source.username, source.password,
        ).use { conn -> conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } } }

    fun pgRows(database: String, sql: String): List<List<Any?>> =
        DriverManager.getConnection(
            "jdbc:postgresql://${source.host}:${source.firstMappedPort}/$database",
            source.username, source.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) })
                    }
                }
            }
        }

    fun mssqlExec(database: String, vararg sqls: String) {
        val url = "jdbc:sqlserver://${target.host}:${mssqlPort()};databaseName=$database;encrypt=false"
        DriverManager.getConnection(url, target.username, target.password).use { conn ->
            conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
        }
    }

    fun mssqlRows(sql: String): List<List<Any?>> {
        val url = "jdbc:sqlserver://${target.host}:${mssqlPort()};databaseName=geog_tgt;encrypt=false"
        return DriverManager.getConnection(url, target.username, target.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) })
                    }
                }
            }
        }
    }

    /** Die erzeugte T-SQL-Datei anwenden: `GO`-Trenner und Kommentare weg. */
    fun applyGeneratedTsql(script: Path) {
        val statements = script.readText()
            .split(Regex("(?im)^\\s*GO\\s*$"))
            .map { batch -> batch.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").trim() }
            .filter { it.isNotEmpty() }
        val url = "jdbc:sqlserver://${target.host}:${mssqlPort()};databaseName=geog_tgt;encrypt=false"
        DriverManager.getConnection(url, target.username, target.password).use { conn ->
            conn.createStatement().use { stmt -> statements.forEach { stmt.execute(it) } }
        }
    }

    beforeSpec {
        source.start()
        target.start()
        tmp = Files.createTempDirectory("dmigrate-e2e-pg-geography-")

        val areasDdl = """
            CREATE TABLE geo_areas (
                id    integer NOT NULL PRIMARY KEY,
                label varchar(50) NOT NULL,
                area  geography(Point, 4326)
            )
        """.trimIndent()
        pgExec(
            source.databaseName,
            "CREATE EXTENSION IF NOT EXISTS postgis",
            areasDdl,
            // Eine Zeile ohne Geometrie: die NULL-Bindung laeuft ueber einen
            // anderen Zweig als der WKB-Wert und darf nicht mitscheitern.
            """
            INSERT INTO geo_areas (id, label, area) VALUES
              (1, 'Kassel', ST_GeogFromText('SRID=4326;POINT(9.4797 51.3127)')),
              (2, 'Bremen', ST_GeogFromText('SRID=4326;POINT(8.8017 53.0793)')),
              (3, 'ohne',   NULL)
            """.trimIndent(),
        )
        // Das Ziel des zweiten Falls: dieselbe Tabelle in einer zweiten
        // Datenbank, mit einer **bestehenden**, von Hand angelegten
        // `geography`-Spalte.
        pgExec(source.databaseName, "CREATE DATABASE $TARGET_DB")
        pgExec(TARGET_DB, "CREATE EXTENSION IF NOT EXISTS postgis", areasDdl)
        mssqlExec("master", "CREATE DATABASE geog_tgt")
    }

    afterSpec {
        source.stop()
        target.stop()
        tmp.deleteRecursively()
    }

    test("postgis geography to sql server: the values and their SRID reach the target") {
        val schemaYaml = tmp.resolve("schema.yaml")
        val reverse = runRealCli(
            listOf(
                "schema", "reverse",
                "--source", pgUrl(source.databaseName),
                "--output", schemaYaml.absolutePathString(),
            ),
        )
        withClue("reverse stderr:\n${reverse.stderr}") { reverse.exitCode shouldBe 0 }
        // Vor P4 stand hier `type: enum` mit `ref_type: geography`, und das
        // Schema war mit `E007` ungueltig.
        val yaml = schemaYaml.readText()
        yaml shouldContain "type: geometry"
        yaml shouldContain "srid: 4326"

        val script = tmp.resolve("schema.sql")
        val generate = runRealCli(
            listOf(
                "schema", "generate",
                "--source", schemaYaml.absolutePathString(),
                "--target", "mssql",
                "--output", script.absolutePathString(),
                "--deterministic",
            ),
        )
        withClue("generate stderr:\n${generate.stderr}") { generate.exitCode shouldBe 0 }
        // 16.9: der geodaetische SRID waehlt `geography` am Ziel.
        script.readText() shouldContain "geography"
        applyGeneratedTsql(script)

        val transfer = runRealCli(
            listOf(
                "data", "transfer",
                "--source", pgUrl(source.databaseName),
                "--target", mssqlUrl(),
                "--tables", "geo_areas",
            ),
        )
        withClue("transfer stdout:\n${transfer.stdout}\nstderr:\n${transfer.stderr}") {
            transfer.exitCode shouldBe 0
        }

        mssqlRows(
            "SELECT label, area.STSrid, ROUND(area.Long, 4) FROM geo_areas ORDER BY id",
        ) shouldContainExactly listOf(
            listOf("Kassel", 4326, 9.4797),
            listOf("Bremen", 4326, 8.8017),
            listOf("ohne", null, null),
        )
    }

    test("postgis geography to an existing geography column: the values arrive with their SRID") {
        val transfer = runRealCli(
            listOf(
                "data", "transfer",
                "--source", pgUrl(source.databaseName),
                "--target", pgUrl(TARGET_DB),
                "--tables", "geo_areas",
            ),
        )
        withClue("transfer stdout:\n${transfer.stdout}\nstderr:\n${transfer.stderr}") {
            transfer.exitCode shouldBe 0
        }

        pgRows(
            TARGET_DB,
            "SELECT label, ST_SRID(area), ROUND(ST_X(area::geometry)::numeric, 4)::float8 " +
                "FROM geo_areas ORDER BY id",
        ) shouldContainExactly listOf(
            listOf("Kassel", 4326, 9.4797),
            listOf("Bremen", 4326, 8.8017),
            listOf("ohne", null, null),
        )
    }
})

/** Die zweite Datenbank auf demselben Server — das Ziel des zweiten Falls. */
private const val TARGET_DB = "dmigrate_geog_tgt"
