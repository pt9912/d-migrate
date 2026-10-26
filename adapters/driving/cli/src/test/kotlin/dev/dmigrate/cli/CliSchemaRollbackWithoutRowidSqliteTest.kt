package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.sqlite.SqliteDriver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Rundlauf je Fall ueber die CLI: `schema migrate --execute --generate-rollback`, `schema rollback --execute
 * --allow-destructive`, Folgeplan. Der Rueckweg stellt das Merkmal `WITHOUT ROWID` her, das die Datenbank vor dem
 * Lauf fuehrte; die Datei aendert es nicht. Die Felder `rollbackComplete` und `postUpVerified` im Kopf des
 * Down-Skripts sind in allen Faellen `true` und sagen darueber nichts: geprueft wird der Zustand der Datenbank (`sqlite_master`).
 */
class CliSchemaRollbackWithoutRowidSqliteTest : FunSpec({

    afterSpec {
        DatabaseDriverRegistry.clear()
        DatabaseDriverRegistry.loadAll()
    }

    fun run(vararg args: String): Int {
        DatabaseDriverRegistry.clear()
        DatabaseDriverRegistry.register(SqliteDriver())
        return try {
            DMigrate().subcommands(SchemaCommand()).parse(args.toList())
            0
        } catch (e: ProgramResult) {
            e.statusCode
        }
    }

    fun exec(db: Path, vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
        }
    }

    fun rows(db: Path, query: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(query).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    fun tableSql(db: Path) = rows(db, "SELECT sql FROM sqlite_master WHERE name = 't'").single()

    /** Die Tabellen-SQL ohne Quoting, Gross-/Kleinschreibung und Leerraum: nur fuer Faelle ohne Neubau stabil. */
    fun normalized(sql: String) = sql.replace("\"", "").replace("`", "").lowercase().replace(Regex("\\s+"), " ")

    fun indexSql(db: Path) = rows(db, "SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 't' AND sql IS NOT NULL")

    fun yaml(withoutRowid: Boolean, caseE: Boolean): String {
        val meta = if (withoutRowid) "    metadata:\n      without_rowid: true\n" else ""
        // Fall E: kein Neubau; der Index steht in der Datei, die Spalte `n` kommt hinzu.
        val u = if (caseE) "      u: { type: integer }\n      n: { type: text }\n" else "      u: { type: integer, unique: true }\n"
        val indices = if (caseE) "    indices:\n      - name: ux\n        columns: [u]\n        unique: true\n" else ""
        return "schema_format: \"1.0\"\nname: \"k\"\nversion: \"0.0.1\"\ntables:\n  t:\n    columns:\n" +
            "      id: { type: integer, required: true }\n$u    primary_key: [id]\n$meta$indices"
    }

    fun database(dir: Path, withoutRowidTable: Boolean): Path {
        val db = dir.resolve("db.sqlite")
        exec(
            db,
            "CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY, u INTEGER)" + if (withoutRowidTable) " WITHOUT ROWID" else "",
            "CREATE UNIQUE INDEX ux ON t(u)",
            "INSERT INTO t VALUES (1, 5)",
        )
        return db
    }

    fun plan(dir: Path, source: Path, db: Path, name: String): String {
        val report = dir.resolve("$name.json")
        run(
            "schema", "migrate", "--source", source.toString(), "--target", "db:sqlite://$db",
            "--plan-only", "--report", report.toString(),
        ) shouldBe 0
        return Files.readString(report)
    }

    fun kinds(report: String) = Regex("\"kind\":\"(\\w+)\"").findAll(report).map { it.groupValues[1] }.toList()

    fun operationIds(report: String) = Regex("\"id\":\"([^\"]+)\"").findAll(report).map { it.groupValues[1] }.toList()

    fun w164(report: String) = Regex("\"code\":\"W164\"").findAll(report).count()

    data class Case(
        val name: String,
        val databaseWithoutRowid: Boolean,
        val fileWithoutRowid: Boolean,
        val caseE: Boolean = false,
        val expectedKinds: List<String> = listOf("AddConstraint", "DropIndex"),
        val expectedW164: Int = 0,
    )

    val cases = listOf(
        Case("A", databaseWithoutRowid = false, fileWithoutRowid = true, expectedW164 = 1),
        Case("B", databaseWithoutRowid = false, fileWithoutRowid = false),
        Case("C1", databaseWithoutRowid = true, fileWithoutRowid = true),
        Case("C2", databaseWithoutRowid = true, fileWithoutRowid = false),
        Case("E", databaseWithoutRowid = false, fileWithoutRowid = true, caseE = true, expectedKinds = listOf("AddColumn")),
    )

    for (case in cases) {
        test("Rundlauf Fall ${case.name}: Up, Rollback und Folgeplan lassen die Datenbank wie vor dem Lauf") {
            val dir = Files.createTempDirectory("cli-wr-roundtrip-${case.name}")
            val db = database(dir, case.databaseWithoutRowid)
            val source = dir.resolve("s.yaml").also { Files.writeString(it, yaml(case.fileWithoutRowid, case.caseE)) }
            val before = tableSql(db)
            val planBefore = plan(dir, source, db, "plan-before")
            kinds(planBefore) shouldBe case.expectedKinds
            w164(planBefore) shouldBe case.expectedW164

            // Hinweg: das Merkmal folgt der Datenbank, die Datei aendert es nicht.
            val down = dir.resolve("down.sql")
            run(
                "schema", "migrate", "--source", source.toString(), "--target", "db:sqlite://$db", "--execute",
                "--generate-rollback", "--output", dir.resolve("up.sql").toString(),
                "--rollback-output", down.toString(), "--report", dir.resolve("up.json").toString(),
            ) shouldBe 0
            tableSql(db).contains("WITHOUT ROWID") shouldBe case.databaseWithoutRowid
            w164(Files.readString(dir.resolve("up.json"))) shouldBe case.expectedW164

            // Das Down-Skript traegt das Merkmal der Datenbank; die Kopfwerte sind in jedem Fall wahr.
            val downText = Files.readString(down)
            downText shouldContain "\"rollbackComplete\":true"
            downText shouldContain "\"postUpVerified\":true"
            downText.contains("WITHOUT ROWID") shouldBe case.databaseWithoutRowid

            // Rueckweg: dieselbe Tabellenform und der Index `ux` wie vor dem Lauf.
            run(
                "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
                "--execute", "--allow-destructive",
            ) shouldBe 0
            tableSql(db).contains("WITHOUT ROWID") shouldBe before.contains("WITHOUT ROWID")
            tableSql(db) shouldNotContain "UNIQUE"
            // Der Neubau (A, B, C1, C2) schreibt den Primaerschluessel als Tabellenconstraint statt inline: dort ist die
            // SQL nicht stabil vergleichbar, es bleiben Merkmal und Spaltenliste. Fall E baut nicht neu und ist stabil.
            if (case.caseE) normalized(tableSql(db)) shouldBe normalized(before)
            rows(db, "SELECT name FROM pragma_table_info('t') ORDER BY cid") shouldBe listOf("id", "u")
            indexSql(db).single().replace("\"", "") shouldContain "UNIQUE INDEX ux ON t"
            rows(db, "SELECT id || ',' || u FROM t") shouldBe listOf("1,5")

            // Folgeplan: dieselben Operationen und dieselbe Warnung wie vor dem Lauf, keine Operation fuers Merkmal.
            val planAfter = plan(dir, source, db, "plan-after")
            kinds(planAfter) shouldBe case.expectedKinds
            operationIds(planAfter) shouldBe operationIds(planBefore)
            w164(planAfter) shouldBe case.expectedW164
        }
    }
})
