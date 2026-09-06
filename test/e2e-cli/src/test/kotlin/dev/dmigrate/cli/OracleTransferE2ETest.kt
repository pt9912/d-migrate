package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText

/**
 * Oracle Slice 3: der vollständige Weg PostgreSQL → Oracle über die ECHTE
 * CLI — `schema reverse` (PG), `schema generate --target oracle`, Anwenden
 * des Skripts per JDBC (die `/`-Batch-Trenner aus Slice 1 sind reine
 * SQL*Plus-Konvention, ein einzelnes JDBC-`execute()` pro Statement braucht
 * sie nicht) und `data transfer` in das erzeugte Schema.
 *
 * Deckt am lebenden System ab, was Unit-Tests nicht können: den
 * ALWAYS-zu-BY-DEFAULT-Identity-Toggle für mitgelieferte Identity-Werte
 * (Oracle kennt kein `OVERRIDING SYSTEM VALUE`), NUMBER/VARCHAR2/TIMESTAMP
 * WITH TIME ZONE-Bindung über Dialektgrenzen und den Reseed danach.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class OracleTransferE2ETest : FunSpec({

    val source = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_src")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    val target = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var tmp: Path

    fun pgUrl(): String =
        "postgresql://${source.username}:${source.password}@${source.host}:${source.firstMappedPort}/${source.databaseName}"

    fun oracleUrl(): String =
        "oracle://${target.username}:${target.password}@${target.host}:${target.oraclePort}/${target.databaseName}"

    /**
     * `getObject()` liefert Oracle-`NUMBER` als `BigDecimal` -- Oracle
     * speichert dabei keine unbedeutenden Nachkommastellen (`42.00` ->
     * `42`, Skala 0), weshalb eine pauschale "Skala 0 -> Int"-Regel eine
     * echte Dezimalspalte wie `amount` mit zufaellig ganzzahligem Wert
     * (Zeile 3: 42.00) faelschlich auf `Int` normalisieren wuerde und dann
     * nicht mehr gegen `BigDecimal("42")` gleich waere. [decimalColumns]
     * nennt deshalb explizit die 1-basierten Spaltenindizes, die immer
     * `BigDecimal` bleiben; alle anderen `NUMBER`-Spalten (IDs) werden zu
     * `Int`. Ein unbegrenztes `TEXT` (PG-Quelle `email`) faellt beim
     * Generate auf `CLOB` -- als live Locator, nicht als `String`
     * (dieselbe Materialisierung wie [OracleDataReader.mapValue], hier nur
     * fuer die Roh-Verifikationsabfrage nachgebildet).
     */
    fun normalizeForAssertion(value: Any?, isDecimalColumn: Boolean): Any? = when {
        value is java.math.BigDecimal && !isDecimalColumn -> value.toInt()
        value is java.sql.Clob -> value.getSubString(1, value.length().toInt())
        else -> value
    }

    fun oracleRows(sql: String, decimalColumns: Set<Int> = emptySet()): List<List<Any?>> =
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                (1..rs.metaData.columnCount).map {
                                    normalizeForAssertion(rs.getObject(it), it in decimalColumns)
                                },
                            )
                        }
                    }
                }
            }
        }

    /**
     * Fuehrt das generierte Skript per JDBC aus. Die `/`-Zeilen sind reine
     * SQL*Plus-Batch-Konvention (siehe `DialectCapabilities.batchSeparator`)
     * -- ein einzelnes `Statement.execute()` pro Anweisung braucht sie nicht.
     * Kommentarbloecke (Header, `-- [Wxxx]`-Hinweise) faellt bei dieser
     * bewusst kommentarfreien Beispielschema-Groesse nicht an.
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
        tmp = Files.createTempDirectory("dmigrate-e2e-oracle-transfer-")

        DriverManager.getConnection(
            "jdbc:postgresql://${source.host}:${source.firstMappedPort}/${source.databaseName}",
            source.username, source.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE customers (
                        id      SERIAL PRIMARY KEY,
                        email   TEXT NOT NULL UNIQUE,
                        name    VARCHAR(100) NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE orders (
                        id          SERIAL PRIMARY KEY,
                        customer_id INTEGER NOT NULL REFERENCES customers(id),
                        amount      NUMERIC(10,2) NOT NULL,
                        note        TEXT
                    )
                    """.trimIndent(),
                )
            }
            conn.prepareStatement("INSERT INTO customers (email, name) VALUES (?, ?)").use { ps ->
                ps.setString(1, "alice@test.com"); ps.setString(2, "Alice Ähnlich"); ps.execute()
                ps.setString(1, "bob@test.com"); ps.setString(2, "Bob"); ps.execute()
            }
            conn.prepareStatement("INSERT INTO orders (customer_id, amount, note) VALUES (?, ?, ?)").use { ps ->
                ps.setInt(1, 1); ps.setBigDecimal(2, java.math.BigDecimal("99.95")); ps.setString(3, "erste"); ps.execute()
                ps.setInt(1, 1); ps.setBigDecimal(2, java.math.BigDecimal("10.50")); ps.setNull(3, java.sql.Types.VARCHAR); ps.execute()
                ps.setInt(1, 2); ps.setBigDecimal(2, java.math.BigDecimal("42.00")); ps.setString(3, "zwei"); ps.execute()
            }
        }
    }

    afterSpec {
        source.stop()
        target.stop()
        tmp.deleteRecursively()
    }

    test("postgres to oracle: reverse, generate, apply, transfer") {
        val schemaYaml = tmp.resolve("schema.yaml")
        val reverse = runRealCli(
            listOf("schema", "reverse", "--source", pgUrl(), "--output", schemaYaml.absolutePathString()),
        )
        withClue("reverse stderr:\n${reverse.stderr}") { reverse.exitCode shouldBe 0 }

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
        script.readText() shouldContain "GENERATED ALWAYS AS IDENTITY"

        applyGeneratedDdl(script)

        val transfer = runRealCli(
            listOf(
                "data", "transfer",
                "--source", pgUrl(),
                "--target", oracleUrl(),
                "--tables", "customers,orders",
            ),
        )
        withClue("transfer stdout:\n${transfer.stdout}\nstderr:\n${transfer.stderr}") {
            transfer.exitCode shouldBe 0
        }

        // Schlüssel bleiben erhalten (ALWAYS-zu-BY-DEFAULT-Identity-Toggle), Unicode und Dezimalstellen auch.
        // Bezeichner durchgehend quoted-lowercase: OracleDdlGenerator legt die
        // Tabellen so an, ein unquoted Verweis wuerde auf GROSSSCHREIBUNG
        // falten und mit ORA-00942 scheitern.
        oracleRows("SELECT \"id\", \"email\", \"name\" FROM \"customers\" ORDER BY \"id\"") shouldContainExactly listOf(
            listOf(1, "alice@test.com", "Alice Ähnlich"),
            listOf(2, "bob@test.com", "Bob"),
        )
        // Oracles NUMBER(p,s) ist eine Praezisions-/Skalen-OBERGRENZE, keine
        // feste Formatierung wie DECIMAL anderswo: unbedeutende Nachkommastellen
        // werden nicht gespeichert (10.50 -> 10.5, 42.00 -> 42) -- reales,
        // dokumentiertes Oracle-Verhalten, kein Transfer-Defekt.
        oracleRows(
            "SELECT \"id\", \"customer_id\", \"amount\", \"note\" FROM \"orders\" ORDER BY \"id\"",
            decimalColumns = setOf(3),
        ) shouldContainExactly listOf(
            listOf(1, 1, java.math.BigDecimal("99.95"), "erste"),
            listOf(2, 1, java.math.BigDecimal("10.5"), null),
            listOf(3, 2, java.math.BigDecimal("42"), "zwei"),
        )

        // Nach dem Transfer vergibt Oracle kollisionsfrei weiter (Identity-Reseed).
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { conn ->
            conn.prepareStatement("INSERT INTO \"customers\" (\"email\", \"name\") VALUES (?, ?)").use { ps ->
                ps.setString(1, "carol@test.com")
                ps.setString(2, "Carol")
                ps.execute()
            }
        }
        // `email` faellt auf CLOB (siehe oben) -- CLOB in einem WHERE-Gleichheits-
        // praedikat waere ORA-22848; die Suche laeuft deshalb ueber "name" (VARCHAR2).
        oracleRows("SELECT \"id\" FROM \"customers\" WHERE \"name\" = 'Carol'").single().single() shouldBe 3
    }
})
