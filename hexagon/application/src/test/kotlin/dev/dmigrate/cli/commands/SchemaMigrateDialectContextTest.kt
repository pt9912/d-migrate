package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.MssqlHashPartitionMode
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.RoutineCapabilityDefaults
import dev.dmigrate.driver.SqliteNamedSequenceMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * Die Naht, an der ein Dialekt im **Migrationspfad** seine Modus-Schalter
 * bekommt.
 *
 * Sie hatte eine Luecke: MSSQL fiel auf `DdlDialectContext.None`, wodurch der
 * gesamte HASH-Emulationszweig des Renderers unerreichbar war. Der Test, der
 * das haette finden sollen, baute die Optionen selbst und umging genau diese
 * Stelle — er war gruen und hat nichts belegt. Diese Spec prueft die Naht.
 */
class SchemaMigrateDialectContextTest : FunSpec({

    fun request(mssqlHash: String? = null, sqliteSeq: String? = null, mysqlSeq: String? = null) =
        SchemaMigrateRequest(
            source = "file:${Path.of("schema.yaml")}",
            target = "db:placeholder",
            dialect = DatabaseDialect.MSSQL,
            mssqlHashPartitions = mssqlHash,
            sqliteNamedSequences = sqliteSeq,
            mysqlNamedSequences = mysqlSeq,
        )

    fun contextFor(dialect: DatabaseDialect, request: SchemaMigrateRequest) =
        SchemaMigrateRenderPipeline.dialectContextFor(
            request = request,
            dialect = dialect,
            routineCapability = RoutineCapabilityDefaults.forDialect(dialect),
            mysqlServerVersion = null,
            mysqlSequenceDeclarations = emptyList(),
            probeOutcome = null,
            castPreflights = emptyList(),
        )

    test("mssql without the flag stays on the conservative default") {
        val ctx = contextFor(DatabaseDialect.MSSQL, request())
        ctx shouldBe DdlDialectContext.MsSql(MssqlHashPartitionMode.ACTION_REQUIRED)
    }

    // Der eigentliche Defekt: hier stand `DdlDialectContext.None`, und der
    // Renderer sah den Modus nie.
    test("mssql with computed_column reaches the renderer") {
        val ctx = contextFor(DatabaseDialect.MSSQL, request(mssqlHash = "computed_column"))
        ctx shouldBe DdlDialectContext.MsSql(MssqlHashPartitionMode.COMPUTED_COLUMN)
    }

    test("an unknown value falls back instead of throwing") {
        val ctx = contextFor(DatabaseDialect.MSSQL, request(mssqlHash = "nonsense"))
        ctx shouldBe DdlDialectContext.MsSql(MssqlHashPartitionMode.ACTION_REQUIRED)
    }

    test("the other dialects are unaffected") {
        contextFor(DatabaseDialect.POSTGRESQL, request()) shouldBe DdlDialectContext.None
        (contextFor(DatabaseDialect.SQLITE, request(sqliteSeq = "helper_table")) as DdlDialectContext.Sqlite)
            .namedSequenceMode shouldBe SqliteNamedSequenceMode.HELPER_TABLE
        (contextFor(DatabaseDialect.MYSQL, request(mysqlSeq = "helper_table")) as DdlDialectContext.MySql)
            .namedSequenceMode shouldBe MysqlNamedSequenceMode.HELPER_TABLE
    }
})
