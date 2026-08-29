package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as strShouldContain
import io.kotest.matchers.string.shouldNotContain as strShouldNotContain
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager

// Routinen und Trigger gegen echtes SQL Server 2022: Rumpf-Schnitt (9a),
// Renderung und Round-Trip (9b), und die Konstrukte, fuer die das neutrale
// Modell kein Feld hat.
class MssqlRoutineIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(MssqlDriver())
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE DATABASE dmigrate_routines")
            }
        }
        config = ConnectionConfig(
            dialect = DatabaseDialect.MSSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_routines",
            user = container.username,
            password = container.password,
            ssl = SslSettings(SslMode.DISABLE),
        )
    }

    afterSpec { container.stop() }

    // Der Reverse liest Routinen-Ruempfe aus `sys.sql_modules`. `R342` bleibt
    // fuer das, was dort keinen Text hat: CLR und `WITH ENCRYPTION`.
    test("routine and trigger bodies are read back") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            try {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE TABLE audit_src (id INT NOT NULL PRIMARY KEY, note NVARCHAR(50))")
                        stmt.execute(
                            "CREATE FUNCTION dbo.fn_double (@x INT) RETURNS INT AS BEGIN RETURN @x * 2 END",
                        )
                        stmt.execute("CREATE PROCEDURE dbo.sp_touch AS SELECT 1")
                        stmt.execute(
                            "CREATE TRIGGER trg_audit ON audit_src AFTER INSERT, UPDATE AS SELECT 1",
                        )
                    }
                }

                val result = MssqlSchemaReader().read(pool)

                // Der Rumpf ist der innere Block, nicht die ganze Anweisung:
                // Signatur und Rueckgabetyp stehen als eigene Felder daneben.
                val fn = result.schema.functions.getValue("fn_double(in:integer)")
                fn.body.shouldNotBeNull() strShouldContain "@x * 2"
                fn.body.shouldNotBeNull() strShouldNotContain "CREATE FUNCTION"
                fn.parameters.map { it.name } shouldBe listOf("x")
                // Neutraler Typname, nicht `int` — er geht in den Key ein.
                fn.parameters.map { it.type } shouldBe listOf("integer")
                fn.returns?.type shouldBe "integer"
                fn.sourceDialect shouldBe "mssql"

                val sp = result.schema.procedures.getValue("sp_touch()")
                sp.body.shouldNotBeNull() strShouldContain "SELECT 1"
                sp.body.shouldNotBeNull() strShouldNotContain "CREATE PROCEDURE"

                val trigger = result.schema.triggers.getValue("audit_src::trg_audit")
                trigger.table shouldBe "audit_src"
                trigger.timing shouldBe TriggerTiming.AFTER
                trigger.events shouldBe setOf(TriggerEvent.INSERT, TriggerEvent.UPDATE)
                // T-SQL-Trigger feuern je Anweisung, nicht je Zeile.
                trigger.forEach shouldBe TriggerForEach.STATEMENT
                trigger.body.shouldNotBeNull() strShouldNotContain "CREATE TRIGGER"

                // Und R342 meldet sie nicht mehr als ungelesen.
                result.skippedObjects.none { it.name in setOf("fn_double", "sp_touch", "trg_audit") } shouldBe true
            } finally {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        runCatching { stmt.execute("DROP TRIGGER trg_audit") }
                        runCatching { stmt.execute("DROP PROCEDURE dbo.sp_touch") }
                        runCatching { stmt.execute("DROP FUNCTION dbo.fn_double") }
                        runCatching { stmt.execute("DROP TABLE audit_src") }
                    }
                }
            }
        }
    }

    // Was der Reverse liest, muss der Generator wieder anwendbar machen —
    // sonst ist der Rumpf-Vertrag nur auf dem Papier erfuellt.
    test("generated T-SQL routines apply against SQL Server and read back unchanged") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            try {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE TABLE rt_src (id INT NOT NULL PRIMARY KEY, amount DECIMAL(10,2))")
                        stmt.execute(
                            "CREATE FUNCTION dbo.rt_total (@id INT, @label VARCHAR(50)) RETURNS DECIMAL(10,2) " +
                                "AS BEGIN RETURN (SELECT SUM(amount) FROM rt_src WHERE id = @id) END",
                        )
                        stmt.execute(
                            "CREATE FUNCTION dbo.rt_rows (@min INT) RETURNS TABLE " +
                                "AS RETURN (SELECT id FROM rt_src WHERE id >= @min)",
                        )
                        stmt.execute(
                            "CREATE PROCEDURE dbo.rt_bump (@id INT, @affected INT OUTPUT) " +
                                "AS BEGIN UPDATE rt_src SET amount = amount + 1 WHERE id = @id; " +
                                "SET @affected = @@ROWCOUNT END",
                        )
                        stmt.execute(
                            "CREATE TRIGGER rt_trg ON rt_src AFTER INSERT, UPDATE AS BEGIN SET NOCOUNT ON END",
                        )
                    }
                }

                val source = MssqlSchemaReader().read(pool).schema
                val generated = MssqlDdlGenerator().generate(source)
                // Kein Objekt darf als E053 liegenbleiben: alle vier Rumpfe sind T-SQL.
                generated.skippedObjects.map { it.name } shouldNotContain "rt_total"
                // Alle `CREATE OR ALTER` des Schemas, nicht nur die vier neuen:
                // die parameterlose Prozedur aus dem Vorlauf gehoert dazu und
                // belegt, dass auch die leere Parameterliste anwendbar ist.
                val routineSql = generated.statements.map { it.sql }
                    .filter { it.startsWith("CREATE OR ALTER") }
                routineSql.count { it.contains("[rt_") } shouldBe 4

                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("DROP TRIGGER rt_trg")
                        stmt.execute("DROP PROCEDURE dbo.rt_bump")
                        stmt.execute("DROP FUNCTION dbo.rt_rows")
                        stmt.execute("DROP FUNCTION dbo.rt_total")
                        // Jede dieser Anweisungen muss allein im Batch stehen —
                        // `execute` je Statement erfuellt das.
                        routineSql.forEach { stmt.execute(it) }
                    }
                }

                val reread = MssqlSchemaReader().read(pool).schema
                reread.functions.keys shouldContain "rt_total(in:integer,in:text)"
                reread.functions.keys shouldContain "rt_rows(in:integer)"
                reread.procedures.keys shouldContain "rt_bump(in:integer,out:integer)"
                reread.triggers.keys shouldContain "rt_src::rt_trg"
                reread.functions.getValue("rt_rows(in:integer)").returns?.type shouldBe "table"
                reread.triggers.getValue("rt_src::rt_trg").events shouldBe
                    setOf(TriggerEvent.INSERT, TriggerEvent.UPDATE)
                // Der Rumpf ueberlebt den Umweg woertlich.
                reread.functions.getValue("rt_total(in:integer,in:text)").body.shouldNotBeNull()
                    .trim() shouldBe source.functions.getValue("rt_total(in:integer,in:text)").body
                    .shouldNotBeNull().trim()
            } finally {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        runCatching { stmt.execute("DROP TRIGGER rt_trg") }
                        runCatching { stmt.execute("DROP PROCEDURE dbo.rt_bump") }
                        runCatching { stmt.execute("DROP FUNCTION dbo.rt_rows") }
                        runCatching { stmt.execute("DROP FUNCTION dbo.rt_total") }
                        runCatching { stmt.execute("DROP TABLE rt_src") }
                    }
                }
            }
        }
    }

    // Vier T-SQL-Konstrukte, fuer die das neutrale Modell kein Feld hat. Bei
    // `WITH EXECUTE AS` verschoebe die Optionsklausel zusaetzlich den
    // Rumpf-Schnitt, denn ihr `AS` ist das erste auf oberster Ebene.
    test("routines carrying constructs the neutral model has no field for are reported") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            try {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE TABLE opt_src (id INT NOT NULL PRIMARY KEY)")
                        stmt.execute("CREATE TYPE opt_tvp AS TABLE (id INT)")
                        stmt.execute(
                            "CREATE PROCEDURE dbo.opt_owner WITH EXECUTE AS OWNER AS BEGIN SELECT 1 END",
                        )
                        stmt.execute(
                            "CREATE FUNCTION dbo.opt_bound () RETURNS INT WITH SCHEMABINDING " +
                                "AS BEGIN RETURN 1 END",
                        )
                        stmt.execute(
                            "CREATE PROCEDURE dbo.opt_table (@t opt_tvp READONLY) AS BEGIN SELECT 1 FROM @t END",
                        )
                        stmt.execute("CREATE TRIGGER opt_ddl ON DATABASE FOR DROP_TABLE AS SELECT 1")
                    }
                }

                val result = MssqlSchemaReader().read(pool)
                val codeOf = result.skippedObjects.associate { it.name to it.code }

                codeOf["opt_owner"] shouldBe "R351"
                codeOf["opt_bound"] shouldBe "R351"
                codeOf["opt_table"] shouldBe "R352"
                // Gemessen: ein datenbankweiter DDL-Trigger ist nicht
                // schemagebunden und faellt schon aus `SCHEMA_ID(?)` heraus —
                // er erreicht den Reverse gar nicht.
                codeOf["opt_ddl"].shouldBeNull()
                result.schema.triggers.keys.none { it.endsWith("::opt_ddl") } shouldBe true
                // Keiner davon darf mit halbem Inhalt im Schema landen.
                result.schema.procedures.keys shouldNotContain "opt_owner()"
                result.schema.functions.keys shouldNotContain "opt_bound()"
            } finally {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        runCatching { stmt.execute("DROP TRIGGER opt_ddl ON DATABASE") }
                        runCatching { stmt.execute("DROP PROCEDURE dbo.opt_table") }
                        runCatching { stmt.execute("DROP FUNCTION dbo.opt_bound") }
                        runCatching { stmt.execute("DROP PROCEDURE dbo.opt_owner") }
                        runCatching { stmt.execute("DROP TYPE opt_tvp") }
                        runCatching { stmt.execute("DROP TABLE opt_src") }
                    }
                }
            }
        }
    }

    // Was der Diff-Pfad rendert, muss der Server annehmen — und hinterher muss
    // dastehen, was im Zielschema stand.
    test("migrate creates, replaces and drops routines against SQL Server") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            try {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE TABLE mig_src (id INT NOT NULL PRIMARY KEY, amount DECIMAL(10,2))")
                        stmt.execute(
                            "CREATE FUNCTION dbo.mig_total (@id INT) RETURNS DECIMAL(10,2) " +
                                "AS BEGIN RETURN 0 END",
                        )
                        stmt.execute("CREATE PROCEDURE dbo.mig_gone AS BEGIN SELECT 1 END")
                    }
                }

                val current = MssqlSchemaReader().read(pool).schema
                val fnKey = "mig_total(in:integer)"
                val desired = current.copy(
                    // Rumpf ersetzt, Prozedur entfernt, Trigger neu.
                    functions = mapOf(
                        fnKey to current.functions.getValue(fnKey).copy(body = "BEGIN RETURN 42 END"),
                    ),
                    procedures = emptyMap(),
                    triggers = mapOf(
                        "mig_src::mig_trg" to TriggerDefinition(
                            table = "mig_src",
                            events = setOf(TriggerEvent.INSERT),
                            timing = TriggerTiming.AFTER,
                            forEach = TriggerForEach.STATEMENT,
                            body = "BEGIN SET NOCOUNT ON END",
                            sourceDialect = "mssql",
                        ),
                    ),
                )

                val diff = SchemaComparator().compare(current, desired)
                val plan = DiffPlanner().plan(current, desired, diff)
                val migration = MssqlDiffDdlGenerator().generateUp(plan, DdlGenerationOptions())
                withClue(migration.blockers.joinToString { it.toString() }) {
                    migration.blockers.shouldBeEmpty()
                }

                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        migration.statements.forEach { stmt.execute(it.sql) }
                    }
                }

                val after = MssqlSchemaReader().read(pool).schema
                after.functions.getValue(fnKey).body.shouldNotBeNull() strShouldContain "RETURN 42"
                after.procedures.keys shouldNotContain "mig_gone()"
                after.triggers.keys shouldContain "mig_src::mig_trg"
            } finally {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        runCatching { stmt.execute("DROP TRIGGER mig_trg") }
                        runCatching { stmt.execute("DROP PROCEDURE dbo.mig_gone") }
                        runCatching { stmt.execute("DROP FUNCTION dbo.mig_total") }
                        runCatching { stmt.execute("DROP TABLE mig_src") }
                    }
                }
            }
        }
    }
})
