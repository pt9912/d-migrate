package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText

/**
 * Der SRID-Weg PostGIS → Oracle über die ECHTE CLI: `schema reverse`,
 * `schema generate --target oracle`, Anwenden per JDBC, `data transfer`.
 *
 * Oracle führt die SRID einer Geometrie nicht auf der Spalte, sondern in
 * `USER_SDO_GEOM_METADATA` — und hebt Tabellen- und Spaltennamen in dieser
 * Zeile bedingungslos hoch, weshalb sie eine quoted-lowercase Tabelle gar
 * nicht beschreiben kann. Das Ziel kann die Angabe also nicht liefern; sie
 * kommt aus dem Quellschema, das `data transfer` ohnehin liest, und tritt
 * beim Binden in den `SDO_UTIL.FROM_WKBGEOMETRY`-Aufruf ein.
 *
 * Was kein Adaptertest zeigen kann: dass die Angabe diesen Weg über die
 * Kommandogrenze hinweg wirklich nimmt — Reverse, Runner und Import-Sitzung
 * liegen in verschiedenen Schichten, und WKB selbst trägt keine SRID.
 *
 * **Eigene Images.** `postgis/postgis` statt `postgres` (die Quelle braucht
 * eine echte Geometriespalte), `23-faststart` statt `23-slim-faststart`
 * (Spatial fehlt in der schlanken Variante).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class OracleSpatialTransferE2ETest : FunSpec({

    val source = PostgreSQLContainer(
        DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"),
    )
        .withDatabaseName("dmigrate_geo_src")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    val target = OracleContainer(TestImages.ORACLE_FULL)
        .withStartupTimeout(Duration.ofMinutes(8))

    lateinit var tmp: Path

    fun pgUrl(): String =
        "postgresql://${source.username}:${source.password}@${source.host}:${source.firstMappedPort}/${source.databaseName}"

    fun oracleUrl(): String =
        "oracle://${target.username}:${target.password}@${target.host}:${target.oraclePort}/${target.databaseName}"

    fun oracleRows(sql: String): List<List<Any?>> =
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { conn ->
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

    /**
     * Führt das generierte Skript per JDBC aus. Die `--`-Zeilen tragen hier
     * die `W120`-Hinweise zur SRID, die `/`-Zeilen sind SQL*Plus-Konvention;
     * ein einzelnes `Statement.execute()` pro Anweisung braucht beide nicht.
     */
    fun applyGeneratedDdl(script: Path) {
        val statements = script.readText()
            .lines()
            .filterNot { it.trim() == "/" || it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { conn ->
            conn.createStatement().use { stmt ->
                for (sql in statements) stmt.execute(sql)
            }
        }
    }

    beforeSpec {
        source.start()
        target.start()
        tmp = Files.createTempDirectory("dmigrate-e2e-oracle-spatial-")

        DriverManager.getConnection(
            "jdbc:postgresql://${source.host}:${source.firstMappedPort}/${source.databaseName}",
            source.username, source.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis")
                stmt.execute(
                    """
                    CREATE TABLE places (
                        id    SERIAL PRIMARY KEY,
                        label VARCHAR(50) NOT NULL,
                        geom  geometry(Point, 4326)
                    )
                    """.trimIndent(),
                )
                // Eine Zeile ohne Geometrie: die NULL-Bindung läuft über einen
                // anderen Zweig als der WKB-Wert und darf nicht mitscheitern.
                stmt.execute(
                    """
                    INSERT INTO places (label, geom) VALUES
                        ('Bremen', ST_SetSRID(ST_MakePoint(8.8017, 53.0793), 4326)),
                        ('Kassel', ST_SetSRID(ST_MakePoint(9.4797, 51.3127), 4326)),
                        ('ohne',   NULL)
                    """.trimIndent(),
                )
            }
        }
    }

    afterSpec {
        source.stop()
        target.stop()
        tmp.deleteRecursively()
    }

    test("postgis to oracle: the source SRID survives reverse, generate and transfer") {
        val schemaYaml = tmp.resolve("schema.yaml")
        val reverse = runRealCli(
            listOf("schema", "reverse", "--source", pgUrl(), "--output", schemaYaml.absolutePathString()),
        )
        withClue("reverse stderr:\n${reverse.stderr}") { reverse.exitCode shouldBe 0 }
        withClue("das Quellschema muss die SRID tragen -- ohne sie hat der Transfer nichts zu übergeben") {
            schemaYaml.readText() shouldContain "4326"
        }

        val script = tmp.resolve("schema.sql")
        val generate = runRealCli(
            listOf(
                "schema", "generate",
                "--source", schemaYaml.absolutePathString(),
                "--target", "oracle",
                "--output", script.absolutePathString(),
                "--deterministic",
            ),
        )
        withClue("generate stderr:\n${generate.stderr}") { generate.exitCode shouldBe 0 }
        // Die Zielspalte ist typlos, was die SRID angeht -- genau der Verlust,
        // den der Transfer aus dem Quellschema ausgleicht.
        script.readText() shouldContain "SDO_GEOMETRY"

        applyGeneratedDdl(script)

        val transfer = runRealCli(
            listOf(
                "data", "transfer",
                "--source", pgUrl(),
                "--target", oracleUrl(),
                "--tables", "places",
            ),
        )
        withClue("transfer stdout:\n${transfer.stdout}\nstderr:\n${transfer.stderr}") {
            transfer.exitCode shouldBe 0
        }

        // Bezeichner quoted-lowercase, wie OracleDdlGenerator sie anlegt.
        oracleRows(
            """
            SELECT t."label", t."geom".SDO_SRID, ROUND(t."geom".SDO_POINT.X, 4)
            FROM "places" t ORDER BY t."id"
            """.trimIndent(),
        ) shouldContainExactly listOf(
            listOf("Bremen", java.math.BigDecimal(4326), java.math.BigDecimal("8.8017")),
            listOf("Kassel", java.math.BigDecimal(4326), java.math.BigDecimal("9.4797")),
            listOf("ohne", null, null),
        )
    }
})
