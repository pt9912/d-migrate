package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Slice 7: das erzeugte `PARTITION BY` wird gegen ein ECHTES Oracle
 * ausgefuehrt, nicht nur gegen eine Zeichenketten-Erwartung.
 *
 * Genau dieser Schritt hat in Slice 3/3b vier Generator-Fehler gefunden, die
 * jeder String-Vergleich fuer richtig gehalten haette (ORA-03076, ORA-04010,
 * ORA-02329, ORA-00955). Partitionierung ist syntaktisch die reichste Form,
 * die der Oracle-Generator erzeugt — hier ist der Abstand zwischen
 * „sieht richtig aus" und „laeuft" am groessten.
 */
class OraclePartitionGenerateIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var config: ConnectionConfig

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
    }

    afterSpec { container.stop() }

    fun table(columns: Map<String, ColumnDefinition>, partitioning: PartitionConfig) =
        TableDefinition(columns = columns, partitioning = partitioning)

    val cases: List<Triple<String, TableDefinition, String>> = listOf(
        Triple(
            "range_dates",
            table(
                mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer),
                    // DateTime, nicht Date: Oracles `DATE` traegt eine Uhrzeit,
                    // der Reverse meldet die Spalte deshalb als DateTime (siehe
                    // OracleNeutralTypeCanonicalizerIntegrationTest). Mit `Date`
                    // waere schon der SPALTENtyp nicht identisch zurueck, und der
                    // Grenzwert-Vergleich unten pruefte dann zwei Dinge auf
                    // einmal. Die Faltung selbst deckt der eigene Test darunter.
                    "d" to ColumnDefinition(NeutralType.DateTime(timezone = false)),
                ),
                PartitionConfig(
                    type = PartitionType.RANGE,
                    key = listOf("d"),
                    partitions = listOf(
                        // Die kanonische Form, wie PG und MySQL sie liefern -- NICHT
                        // Oracle-Syntax. Ein blankes '2024-01-01' gegen eine
                        // DATE-Spalte haengt an NLS_DATE_FORMAT (ORA-01861).
                        PartitionDefinition(
                            name = "p2023", to = listOf(PartitionBound.Value("'2024-01-01 00:00:00'")),
                        ),
                        PartitionDefinition(
                            name = "p2024", to = listOf(PartitionBound.Value("'2025-01-01 00:00:00'")),
                        ),
                        PartitionDefinition(name = "pmax", to = listOf(PartitionBound.MaxValue)),
                    ),
                ),
            ),
            "RANGE",
        ),
        Triple(
            "range_stamps",
            table(
                mapOf("ts" to ColumnDefinition(NeutralType.DateTime(timezone = false))),
                PartitionConfig(
                    type = PartitionType.RANGE,
                    key = listOf("ts"),
                    partitions = listOf(
                        PartitionDefinition(name = "s1", to = listOf(PartitionBound.Value("'2024-01-01 12:30:00'"))),
                        PartitionDefinition(name = "smax", to = listOf(PartitionBound.MaxValue)),
                    ),
                ),
            ),
            "RANGE",
        ),
        Triple(
            "range_multi",
            table(
                mapOf(
                    "a" to ColumnDefinition(NeutralType.Integer),
                    "b" to ColumnDefinition(NeutralType.Integer),
                ),
                PartitionConfig(
                    type = PartitionType.RANGE,
                    key = listOf("a", "b"),
                    partitions = listOf(
                        PartitionDefinition(
                            name = "q1",
                            to = listOf(PartitionBound.Value("10"), PartitionBound.Value("100")),
                        ),
                        PartitionDefinition(
                            name = "q2",
                            to = listOf(PartitionBound.MaxValue, PartitionBound.MaxValue),
                        ),
                    ),
                ),
            ),
            "RANGE",
        ),
        Triple(
            "list_status",
            table(
                mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer),
                    "st" to ColumnDefinition(NeutralType.Text(maxLength = 10)),
                ),
                PartitionConfig(
                    type = PartitionType.LIST,
                    key = listOf("st"),
                    partitions = listOf(
                        PartitionDefinition(name = "l_ab", values = listOf("'A'", "'B'")),
                        // Oracle traegt die DEFAULT-Partition nativ -- anders als
                        // MySQL, das sie verwerfen muss (E063).
                        PartitionDefinition(name = "l_rest", isDefault = true),
                    ),
                ),
            ),
            "LIST",
        ),
        Triple(
            "hash_ids",
            table(
                mapOf("id" to ColumnDefinition(NeutralType.Integer)),
                PartitionConfig(
                    type = PartitionType.HASH,
                    key = listOf("id"),
                    partitions = listOf(
                        PartitionDefinition(name = "h0", modulus = 2, remainder = 0),
                        PartitionDefinition(name = "h1", modulus = 2, remainder = 1),
                    ),
                ),
            ),
            "HASH",
        ),
    )

    cases.forEach { (name, tableDef, expectedType) ->
        test("the generated $expectedType partitioning for '$name' is valid Oracle DDL") {
            val schema = SchemaDefinition(name = "P", version = "1", tables = mapOf(name to tableDef))
            val ddl = OracleDdlGenerator().generate(schema)

            withClue("notes: ${ddl.notes.map { it.code }}") {
                ddl.notes.none { it.code == "E055" } shouldBe true
            }

            HikariConnectionPoolFactory.create(config).use { pool ->
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute(
                            "BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"$name\"'; EXCEPTION WHEN OTHERS THEN NULL; END;",
                        )
                        // Der Kopf des Skripts ist selbst eine "Anweisung",
                        // besteht aber nur aus Kommentarzeilen -- die an Oracle
                        // zu schicken gaebe ORA-00900.
                        ddl.statements
                            .map { it.sql.lines().filterNot { line -> line.trimStart().startsWith("--") } }
                            .map { it.joinToString("\n").trim().removeSuffix(";") }
                            .filter { it.isNotBlank() }
                            .forEach { sql ->
                                withClue("statement failed:\n$sql") { stmt.execute(sql) }
                            }
                    }

                    // Der Katalog ist der Beleg, nicht das Ausbleiben einer
                    // Ausnahme: `CREATE TABLE` ohne gueltige Klausel gaebe eine
                    // unpartitionierte Tabelle, und das faellt sonst nicht auf.
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(
                            "SELECT partitioning_type, partition_count FROM all_part_tables " +
                                "WHERE table_name = '$name'",
                        ).use { rs ->
                            withClue("'$name' ist gar nicht partitioniert angekommen") {
                                rs.next() shouldBe true
                            }
                            rs.getString(1) shouldBe expectedType
                            rs.getInt(2) shouldBe tableDef.partitioning!!.partitions.size
                        }
                    }
                }

                // Round-Trip: was der Generator geschrieben hat, muss der
                // Reverse in derselben kanonischen Form zurueckgeben. Sonst
                // meldete `schema migrate` nach jedem Lauf Drift.
                val readBack = OracleSchemaReader().read(pool)
                    .schema.tables.getValue(name).partitioning
                withClue("'$name' kam unpartitioniert zurueck") { readBack shouldNotBe null }
                readBack!!.type shouldBe tableDef.partitioning!!.type
                readBack.key shouldBe tableDef.partitioning!!.key
                readBack.partitions.map { it.name } shouldBe
                    tableDef.partitioning!!.partitions.map { it.name }
                withClue("Grenzen von '$name'") {
                    readBack.partitions.map { it.to } shouldBe
                        tableDef.partitioning!!.partitions.map { it.to }
                    readBack.partitions.map { it.values } shouldBe
                        tableDef.partitioning!!.partitions.map { it.values }
                    readBack.partitions.map { it.isDefault } shouldBe
                        tableDef.partitioning!!.partitions.map { it.isDefault }
                }
            }
        }
    }

    test("a Date key comes back as DateTime, and its bound gains the midnight time") {
        // Keine Eigenheit der Partitionierung, sondern die Typfaltung: Oracles
        // `DATE` traegt eine Uhrzeit. Hier festgehalten, damit der Round-Trip
        // oben eine reine Identitaet pruefen kann und nicht zwei Dinge zugleich.
        val name = "range_date_fold"
        val schema = SchemaDefinition(
            name = "P", version = "1",
            tables = mapOf(
                name to table(
                    mapOf("d" to ColumnDefinition(NeutralType.Date)),
                    PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("d"),
                        partitions = listOf(
                            PartitionDefinition(name = "p1", to = listOf(PartitionBound.Value("'2024-01-01'"))),
                        ),
                    ),
                ),
            ),
        )
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"$name\"'; EXCEPTION WHEN OTHERS THEN NULL; END;",
                    )
                    OracleDdlGenerator().generate(schema).statements
                        .map { it.sql.lines().filterNot { line -> line.trimStart().startsWith("--") } }
                        .map { it.joinToString("\n").trim().removeSuffix(";") }
                        .filter { it.isNotBlank() }
                        .forEach { sql -> withClue("statement failed:\n$sql") { stmt.execute(sql) } }
                }
            }
            val readBack = OracleSchemaReader().read(pool).schema.tables.getValue(name)
            readBack.columns.getValue("d").type shouldBe NeutralType.DateTime(timezone = false)
            readBack.partitioning!!.partitions.single().to shouldBe
                listOf(PartitionBound.Value("'2024-01-01 00:00:00'"))
        }
    }

    test("a key type Oracle cannot partition on is refused instead of rendered") {
        // Live gemessen: TIMESTAMP WITH TIME ZONE -> ORA-03001,
        // CLOB/BLOB -> ORA-14135. Die Golden-Fixture partitioniert auf genau
        // so einer Spalte -- ohne die Verweigerung schriebe das Golden DDL
        // fest, die Oracle ablehnt.
        listOf(
            NeutralType.DateTime(timezone = true),
            NeutralType.Text(),
        ).forEach { keyType ->
            val schema = SchemaDefinition(
                name = "P", version = "1",
                tables = mapOf(
                    "refused" to table(
                        mapOf("k" to ColumnDefinition(keyType)),
                        PartitionConfig(
                            type = PartitionType.RANGE,
                            key = listOf("k"),
                            partitions = listOf(
                                PartitionDefinition(name = "p1", to = listOf(PartitionBound.MaxValue)),
                            ),
                        ),
                    ),
                ),
            )
            val ddl = OracleDdlGenerator().generate(schema)
            withClue("$keyType") {
                ddl.notes.any { it.code == "E062" } shouldBe true
                ddl.render() shouldNotContain "PARTITION BY"
            }
        }
    }
})
