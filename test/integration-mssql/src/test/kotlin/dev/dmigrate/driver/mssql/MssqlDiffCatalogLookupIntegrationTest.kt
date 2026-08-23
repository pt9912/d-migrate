package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager

/**
 * Live-Belege fuer den MSSQL-Diff-Renderer gegen echtes SQL Server — die
 * Faelle, die ein Unit-Test nicht zeigen kann, weil er nur den Text saehe.
 *
 * Sub-Slice 5a: Default- und Primaerschluessel-Constraints werden **auch dann**
 * geloest, wenn sie nicht d-migrates Namenskonvention tragen.
 *
 * Das ist der Fall, der bei einer fremden Datenbank der Normalfall ist: SQL
 * Server vergibt fuer einen unbenannten Default einen Namen der Form
 * `DF__tabelle__spalte__1A2B3C4D` — mit zufaelligem Suffix, offline also nicht
 * vorhersagbar. Ein konventionsbasiertes `DROP CONSTRAINT IF EXISTS` traefe
 * hier nichts, und das nachfolgende `ALTER COLUMN` scheiterte mit Msg 5074.
 *
 * Genau diese Kette laesst der Test laufen: Tabelle mit auto-benanntem Default
 * anlegen, das gerenderte Statement-Paar ausfuehren, Ergebnis im Katalog
 * pruefen.
 *
 * Sub-Slice 5a-2: der IDENTITY-Tabellen-Neubau. Dass die Sequenz syntaktisch
 * aufgeht, zeigt ein Unit-Test; dass **Schluesselwerte und Zaehler** sie
 * ueberleben, kann nur SQL Server selbst beantworten.
 *
 * Sub-Slice 5d: die Sequenz-Semantik von SQL Server. Sie ist dem Handbuch
 * nicht zu entnehmen und entscheidet, wie der Preserve-Pfad rechnen muss —
 * deshalb steht sie hier als Messung, nicht als Annahme im Kommentar.
 *
 * Sub-Slice 5b: ein **gefilterter** Index laesst sich ueber den Migrate-Pfad
 * anlegen. Das ist der Fall, an dem der sqlcmd-Apply in Slice 2a scheiterte
 * (Msg 1934) — und der Beleg, dass die SET-Optionen im selben Batch wirken.
 */
class MssqlDiffCatalogLookupIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE dmigrate_diff") }
        }
        config = ConnectionConfig(
            dialect = DatabaseDialect.MSSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_diff",
            user = container.username,
            password = container.password,
            ssl = SslSettings(SslMode.DISABLE),
        )
    }

    afterSpec { container.stop() }

    fun exec(vararg statements: String) {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt -> statements.forEach { stmt.execute(it) } }
            }
        }
    }

    fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs -> rs.next(); read(rs) }
                }
            }
        }

    test("ALTER COLUMN succeeds against a default constraint SQL Server named itself") {
        // Kein CONSTRAINT-Name: SQL Server vergibt DF__legacy__nick__<hex>.
        exec("CREATE TABLE legacy (nick NVARCHAR(50) NOT NULL DEFAULT 'anon');")
        val autoName = query(
            "SELECT dc.name FROM sys.default_constraints dc WHERE dc.parent_object_id = OBJECT_ID('legacy')",
        ) { it.getString(1) }
        // Vorbedingung des Tests: der Name folgt NICHT der Konvention.
        autoName shouldNotContain "df_legacy_nick"

        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "legacy" to TableDefinition(
                    columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(50), required = true)),
                ),
            ),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "legacy" to TableDefinition(
                    columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(120), required = true)),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "legacy",
                    columnsChanged = listOf(
                        ColumnDiff(name = "nick", type = ValueChange(NeutralType.Text(50), NeutralType.Text(120))),
                    ),
                ),
            ),
        )
        val rendered = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        exec(*rendered.statements.map { it.sql }.toTypedArray())

        // Die Spalte ist geweitet UND immer noch NOT NULL — ohne das explizite
        // NOT NULL waere sie hier nullable.
        val (length, isNullable) = query(
            "SELECT c.max_length, c.is_nullable FROM sys.columns c " +
                "WHERE c.object_id = OBJECT_ID('legacy') AND c.name = 'nick'",
        ) { it.getInt(1) to it.getBoolean(2) }
        length shouldBe 240 // 120 Zeichen * 2 Byte
        isNullable shouldBe false
    }

    test("DropPrimaryKey removes a key whose name does not follow the convention") {
        exec("CREATE TABLE keyed (id INT NOT NULL CONSTRAINT legacy_pk_name PRIMARY KEY);")
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "keyed" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "keyed" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "keyed", primaryKey = ValueChange(listOf("id"), emptyList()))),
        )
        val rendered = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        exec(*rendered.statements.map { it.sql }.toTypedArray())

        query(
            "SELECT COUNT(*) FROM sys.key_constraints WHERE type = 'PK' AND parent_object_id = OBJECT_ID('keyed')",
        ) { it.getInt(1) } shouldBe 0
    }

    test("a filtered index can be created through the migrate path") {
        // Der Fall, an dem der sqlcmd-Apply in Slice 2a scheiterte: ein
        // gefilterter Index braucht bestimmte SET-Optionen zur DDL-Zeit
        // (Msg 1934). Im Skript stehen sie als eigener Batch voran; hier muessen
        // sie im Statement selbst stecken, weil der Runner einzeln ausfuehrt.
        exec("CREATE TABLE filtered (id INT NOT NULL, nick NVARCHAR(50) NULL);")
        val idx = IndexDefinition(
            name = "ix_filtered_nick",
            columns = listOf(IndexColumn("nick")),
            type = IndexType.BTREE,
            where = "[nick] IS NOT NULL",
        )
        val tableDef = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "nick" to ColumnDefinition(NeutralType.Text(50)),
            ),
            indices = listOf(idx),
        )
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("filtered" to tableDef.copy(indices = emptyList())),
        )
        val desired = SchemaDefinition(name = "App", version = "1", tables = mapOf("filtered" to tableDef))
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "filtered", indicesAdded = listOf(idx))),
        )
        val rendered = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        exec(*rendered.statements.map { it.sql }.toTypedArray())

        query(
            "SELECT COUNT(*) FROM sys.indexes WHERE object_id = OBJECT_ID('filtered') " +
                "AND name = 'ix_filtered_nick' AND has_filter = 1",
        ) { it.getInt(1) } shouldBe 1
    }

    test("the IDENTITY rebuild keeps the key values and continues the counter") {
        // Der Fall, den `ALTER COLUMN` nicht kann (Msg 156): aus einer
        // gewoehnlichen Schluesselspalte wird eine IDENTITY-Spalte. Der
        // Neubau muss dabei DREI Dinge halten, die ein Unit-Test nicht
        // pruefen kann: die vorhandenen Schluesselwerte, den Zaehler und
        // die Objekte, die an der Tabelle hingen.
        exec(
            "CREATE TABLE crew (id INT NOT NULL CONSTRAINT pk_crew PRIMARY KEY, " +
                "nick NVARCHAR(50) NOT NULL CONSTRAINT uq_crew_nick UNIQUE);",
            "INSERT INTO crew (id, nick) VALUES (7, N'ada'), (42, N'grace');",
        )
        val columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "nick" to ColumnDefinition(NeutralType.Text(50), required = true, unique = true),
        )
        val table = TableDefinition(columns = columns, primaryKey = listOf("id"))
        val current = SchemaDefinition(name = "App", version = "1", tables = mapOf("crew" to table))
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "crew" to table.copy(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true), required = true),
                        "nick" to columns.getValue("nick"),
                    ),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "crew",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "id",
                            type = ValueChange(NeutralType.Integer, NeutralType.Identifier(autoIncrement = true)),
                        ),
                    ),
                ),
            ),
        )
        val rendered = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        exec(*rendered.statements.map { it.sql }.toTypedArray())

        // 1. Die Spalte IST jetzt eine Identity-Spalte.
        query("SELECT COLUMNPROPERTY(OBJECT_ID('crew'), 'id', 'IsIdentity')") { it.getInt(1) } shouldBe 1

        // 2. Die Schluesselwerte sind dieselben geblieben — nicht neu vergeben.
        query("SELECT MIN(id), MAX(id), COUNT(*) FROM crew") {
            Triple(it.getInt(1), it.getInt(2), it.getInt(3))
        } shouldBe Triple(7, 42, 2)

        // 3. Der Zaehler steht auf dem hoechsten uebernommenen Wert: die
        //    naechste Zeile bekommt 43, nicht 1 (und kollidiert nicht).
        exec("INSERT INTO crew (nick) VALUES (N'hopper');")
        query("SELECT id FROM crew WHERE nick = N'hopper'") { it.getInt(1) } shouldBe 43

        // 4. Primaerschluessel und UNIQUE tragen wieder ihre endgueltigen
        //    Namen — nicht die der Zwischentabelle.
        query(
            "SELECT COUNT(*) FROM sys.key_constraints WHERE parent_object_id = OBJECT_ID('crew') " +
                "AND name IN ('pk_crew', 'uq_crew_nick')",
        ) { it.getInt(1) } shouldBe 2

        // 5. Die Zwischentabelle ist weg.
        query("SELECT COUNT(*) FROM sys.tables WHERE name LIKE '%__dmg_rebuild_%'") { it.getInt(1) } shouldBe 0
    }

    test("the sequence preserve path resumes without ever reissuing a value") {
        // Diese Zusicherung haelt zwei gemessene Eigenheiten fest, auf denen
        // `MssqlDiffSequenceOps.renderAlterSequenceCurrentValue` aufbaut.
        exec("CREATE SEQUENCE sq_live AS BIGINT START WITH 10 INCREMENT BY 1 NO CYCLE;")

        fun nextValue(): Long = query("SELECT NEXT VALUE FOR sq_live") { it.getLong(1) }
        fun currentValue(): Long =
            query("SELECT CAST(current_value AS BIGINT) FROM sys.sequences WHERE name = 'sq_live'") { it.getLong(1) }

        // (a) Eine frische Sequenz traegt bereits den Startwert, und der erste
        //     Aufruf gibt ihn zurueck, OHNE current_value zu bewegen. Frisch und
        //     einmal benutzt sind daran also nicht zu unterscheiden.
        currentValue() shouldBe 10L
        nextValue() shouldBe 10L
        currentValue() shouldBe 10L
        nextValue() shouldBe 11L
        currentValue() shouldBe 11L

        // Der Renderer setzt bei current_value + Schrittweite fort.
        val probed = currentValue()
        exec("ALTER SEQUENCE [sq_live] RESTART WITH ${probed + 1};")

        // Kein Wert wird ein zweites Mal ausgegeben — darauf kommt es an.
        nextValue() shouldBe 12L

        // (b) RESTART WITH schreibt auch start_value um. Ein Reverse nach der
        //     Migration meldet damit den fortgesetzten Wert als Startwert.
        query("SELECT CAST(start_value AS BIGINT) FROM sys.sequences WHERE name = 'sq_live'") {
            it.getLong(1)
        } shouldBe 12L
    }

    test("the sequence probe reads the live catalog, not a mock of it") {
        // Die Unit-Tests pinnen Abfrageform und Fehlerzuordnung gegen Mocks.
        // Ob die Abfrage gegen einen echten SQL Server ueberhaupt laeuft — CAST,
        // Spaltenalias, Schemafilter — kann nur dieser Test sagen.
        exec(
            "CREATE SCHEMA sales;",
            "CREATE SEQUENCE probe_seq AS BIGINT START WITH 7 INCREMENT BY 1 NO CYCLE;",
            "CREATE SEQUENCE sales.probe_seq AS BIGINT START WITH 700 INCREMENT BY 1 NO CYCLE;",
        )
        exec("DECLARE @x BIGINT = NEXT VALUE FOR probe_seq; SET @x = NEXT VALUE FOR probe_seq;")

        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                fun probe(name: String, schema: String? = null) =
                    MssqlSequenceCurrentValueProbe.probe(
                        conn,
                        SequenceObjectRef(name = name, schema = schema, dialect = RenameProjectionDialect.POSTGRESQL),
                    )

                // Zwei Aufrufe: Startwert 7, danach steht current_value auf 8.
                val read = probe("probe_seq", schema = "dbo")
                read.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
                read.value shouldBe 8L
                read.isCalled shouldBe null

                // Der Schemafilter trifft die richtige der beiden gleichnamigen.
                val sales = probe("probe_seq", schema = "sales")
                sales.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
                sales.value shouldBe 700L

                // Ohne Schemafilter sind zwei gleichnamige nicht entscheidbar.
                val ambiguous = probe("probe_seq")
                ambiguous.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
                ambiguous.code shouldBe MssqlSequenceCurrentValueProbe.CODE_QUERY_FAILED

                probe("does_not_exist") shouldBe SequenceCurrentValueProbeResult.NotFound
            }
        }
    }
})
