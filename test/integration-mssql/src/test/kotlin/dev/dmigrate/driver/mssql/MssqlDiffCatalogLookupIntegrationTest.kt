package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
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
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
})
