package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText

/**
 * Der SRID-Weg SQL Server → PostGIS über die ECHTE CLI.
 *
 * SQL Server führt das Bezugssystem am **Wert**, nicht an der Spalte: das
 * gelesene Schema sagt nur `geometry`, und WKB trägt selbst nichts. Ein
 * Transfer, der nur die Spalte fragt, legt Werte aus UTM 32N am Ziel als
 * SRID 0 ab — die Koordinaten stimmen, ihre Bedeutung nicht.
 *
 * Die Angabe kommt deshalb aus den Werten der Quelle. Führt eine Spalte
 * mehrere Bezugssysteme, gibt es keine richtige Wahl: das Ziel trägt eine
 * SRID je Spalte, jede Entscheidung verschöbe einen Teil der Werte. Der
 * Transfer lehnt diesen Fall ab, statt ihn stumm zu entscheiden.
 *
 * Was kein Adaptertest zeigen kann: dass beides den Weg über die
 * Kommandogrenze nimmt — Reverse, Runner und Import-Sitzung liegen in
 * verschiedenen Schichten.
 *
 * EULA: `acceptLicense()` = `ACCEPT_EULA=Y` (docs/user/quality.md).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class MssqlSpatialTransferE2ETest : FunSpec({

    val source = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2025-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")
        .withStartupTimeout(MSSQL_STARTUP_TIMEOUT)

    val target = PostgreSQLContainer(
        DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"),
    )
        .withDatabaseName("dmigrate_geo_tgt")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var tmp: Path

    fun mssqlPort() = source.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT)

    fun mssqlUrl(): String =
        "mssql://${source.username}:${source.password}@${source.host}:${mssqlPort()}/geo_src?encrypt=false"

    fun pgUrl(): String =
        "postgresql://${target.username}:${target.password}@${target.host}:${target.firstMappedPort}/${target.databaseName}"

    fun mssqlExec(database: String, vararg sqls: String) {
        val url = "jdbc:sqlserver://${source.host}:${mssqlPort()};databaseName=$database;encrypt=false"
        DriverManager.getConnection(url, source.username, source.password).use { conn ->
            conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
        }
    }

    fun pgRows(sql: String): List<List<Any?>> =
        DriverManager.getConnection(
            "jdbc:postgresql://${target.host}:${target.firstMappedPort}/${target.databaseName}",
            target.username, target.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add((1..rs.metaData.columnCount).map { rs.getObject(it) })
                        }
                    }
                }
            }
        }

    fun applyGeneratedDdl(script: Path) {
        val statements = script.readText()
            .lines()
            .filterNot { it.trim().equals("GO", ignoreCase = true) || it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        DriverManager.getConnection(
            "jdbc:postgresql://${target.host}:${target.firstMappedPort}/${target.databaseName}",
            target.username, target.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis")
                for (sql in statements) stmt.execute(sql)
            }
        }
    }

    beforeSpec {
        source.start()
        target.start()
        tmp = Files.createTempDirectory("dmigrate-e2e-mssql-spatial-")

        mssqlExec("master", "CREATE DATABASE geo_src")
        mssqlExec(
            "geo_src",
            """
            CREATE TABLE places (
                id    INT NOT NULL PRIMARY KEY,
                label NVARCHAR(50) NOT NULL,
                utm   geometry NULL
            )
            """.trimIndent(),
            // Eine Zeile ohne Geometrie: die NULL-Bindung läuft über einen
            // anderen Zweig als der WKB-Wert und darf nicht mitscheitern.
            """
            INSERT INTO places (id, label, utm) VALUES
              (1, 'Kassel', geometry::STGeomFromText('POINT(535000 5686000)', 25832)),
              (2, 'Bremen', geometry::STGeomFromText('POINT(487000 5881000)', 25832)),
              (3, 'ohne',   NULL)
            """.trimIndent(),
            // SQL Server lässt mehrere Bezugssysteme in einer Spalte zu.
            """
            CREATE TABLE mixed_places (
                id  INT NOT NULL PRIMARY KEY,
                geo geometry NULL
            )
            """.trimIndent(),
            """
            INSERT INTO mixed_places (id, geo) VALUES
              (1, geometry::STGeomFromText('POINT(1 2)', 4326)),
              (2, geometry::STGeomFromText('POINT(535000 5686000)', 25832))
            """.trimIndent(),
        )
    }

    afterSpec {
        source.stop()
        target.stop()
        tmp.deleteRecursively()
    }

    test("sql server to postgis: the value SRID reaches the target") {
        val schemaYaml = tmp.resolve("schema.yaml")
        val reverse = runRealCli(
            listOf("schema", "reverse", "--source", mssqlUrl(), "--output", schemaYaml.absolutePathString()),
        )
        withClue("reverse stderr:\n${reverse.stderr}") { reverse.exitCode shouldBe 0 }
        // Das Schema kann die Angabe nicht tragen — genau deshalb muss sie
        // aus den Werten kommen.
        schemaYaml.readText() shouldContain "geometry"

        val script = tmp.resolve("schema.sql")
        val generate = runRealCli(
            listOf(
                "schema", "generate",
                "--source", schemaYaml.absolutePathString(),
                "--target", "postgresql",
                "--output", script.absolutePathString(),
                "--deterministic",
            ),
        )
        withClue("generate stderr:\n${generate.stderr}") { generate.exitCode shouldBe 0 }
        applyGeneratedDdl(script)

        val transfer = runRealCli(
            listOf(
                "data", "transfer",
                "--source", mssqlUrl(),
                "--target", pgUrl(),
                "--tables", "places",
            ),
        )
        withClue("transfer stdout:\n${transfer.stdout}\nstderr:\n${transfer.stderr}") {
            transfer.exitCode shouldBe 0
        }

        // Ohne die Wert-Herkunft stünde hier überall 0 — der Typ-Default von
        // `geometry`, und damit ein anderes Bezugssystem als in der Quelle.
        pgRows("SELECT label, ST_SRID(utm), ST_X(utm) FROM places ORDER BY id") shouldContainExactly listOf(
            listOf("Kassel", 25832, 535000.0),
            listOf("Bremen", 25832, 487000.0),
            listOf("ohne", null, null),
        )
    }

    test("a column with two reference systems is refused instead of silently picked") {
        val transfer = runRealCli(
            listOf(
                "data", "transfer",
                "--source", mssqlUrl(),
                "--target", pgUrl(),
                "--tables", "mixed_places",
            ),
        )

        withClue("stdout:\n${transfer.stdout}\nstderr:\n${transfer.stderr}") {
            transfer.exitCode shouldBe 3
        }
        val output = transfer.stdout + transfer.stderr
        output shouldContain "mixed_places"
        output shouldContain "4326, 25832"
    }
})
