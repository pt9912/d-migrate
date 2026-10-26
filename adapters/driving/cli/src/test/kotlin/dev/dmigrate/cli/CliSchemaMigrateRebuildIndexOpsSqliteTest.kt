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
 * Issue #60: Index-Operationen einer Neubau-Tabelle. `--execute` laeuft (Exit 0), die Skripte von `UP` und `DOWN`
 * gelingen ohne den Guard, und der Rueckweg gleicht dem Stand vor dem Lauf.
 */
class CliSchemaMigrateRebuildIndexOpsSqliteTest : FunSpec({

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

    fun scalar(db: Path, query: String): String =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> st.executeQuery(query).use { rs -> rs.next(); rs.getString(1) } }
        }

    fun catalog(db: Path): String =
        scalar(db, "SELECT group_concat(type || ':' || name || ':' || coalesce(sql, ''), char(10)) FROM sqlite_master")

    /** Der Neubau schreibt das `CREATE TABLE` mit Anfuehrungszeichen und Zeilenumbruechen; die Struktur zaehlt. */
    fun structure(db: Path): String =
        catalog(db).replace("\"", "").replace(Regex("\\s*([(),])\\s*"), "$1").replace(Regex("\\s+"), " ")

    fun indexCount(db: Path, name: String) =
        scalar(db, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = '$name'")

    /** Teilt an `;`, haelt aber den Rumpf eines `CREATE TRIGGER` (`BEGIN … ; END;`) zusammen. */
    fun sqlStatements(sql: Path): List<String> {
        val text = Files.readString(sql).lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        val statements = mutableListOf<String>()
        var open = ""
        for (piece in text.split(";")) {
            open = if (open.isEmpty()) piece.trim() else "$open;$piece"
            val unfinishedTrigger = open.uppercase().startsWith("CREATE TRIGGER") && !open.trimEnd().uppercase().endsWith("END")
            if (!unfinishedTrigger) {
                if (open.isNotBlank()) statements += open.trim()
                open = ""
            }
        }
        return statements
    }

    fun write(dir: Path, name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text) }

    fun file(
        columns: String,
        indices: String = "",
        other: String = "",
        constraints: String = "",
        meta: String = "",
    ) = "schema_format: \"1.0\"\nname: \"U\"\nversion: \"1.0\"\ntables:\n$other  t:\n$meta    columns:\n" +
        "      id: { type: integer, required: true }\n$columns    primary_key: [id]\n$constraints$indices"

    fun index(vararg lines: String) = "    indices:\n" + lines.joinToString("") { "      $it\n" }

    val uniqueColumn = "      u: { type: integer, unique: true }\n"
    val plainColumn = "      u: { type: integer }\n"

    fun required(extra: String = "") = "$plainColumn      a: { type: text, required: true }\n      b: { type: text }\n$extra"

    val uniqueIndexDb = listOf(
        "CREATE TABLE t (id INTEGER NOT NULL, u INTEGER, PRIMARY KEY (id))",
        "CREATE UNIQUE INDEX ix_u ON t (u)",
    )
    val textTableDb = "CREATE TABLE t (id INTEGER NOT NULL, u INTEGER, a TEXT, b TEXT, PRIMARY KEY (id))"

    fun database(dir: Path, vararg statements: String): Path = dir.resolve("db.sqlite").also { exec(it, *statements) }

    fun operations(report: Path): Int =
        Regex("\"operationsTotal\":(\\d+)").find(Files.readString(report))!!.groupValues[1].toInt()

    fun execute(dir: Path, source: String, db: Path, name: String): Pair<Int, Path> {
        val report = dir.resolve("$name.json")
        val code = run(
            "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--execute", "--report", report.toString(),
        )
        return code to report
    }

    fun secondRun(dir: Path, source: String, db: Path): Int {
        val report = dir.resolve("again.json")
        run(
            "schema", "migrate", "--source", write(dir, "again.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--output", dir.resolve("again.sql").toString(), "--report", report.toString(),
        ) shouldBe 0
        return operations(report)
    }

    /** Schreibt `UP` und `DOWN` ohne Ausfuehrung; der Plan endet mit Exit 0, auch wo `--execute` blockierte. */
    fun scripts(dir: Path, source: String, db: Path): Pair<Path, Path> {
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")
        run(
            "schema", "migrate", "--source", write(dir, "plan.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--generate-rollback", "--output", up.toString(),
            "--rollback-output", down.toString(), "--report", dir.resolve("plan.json").toString(),
        ) shouldBe 0
        return up to down
    }

    // ── P0.1 / P0.10: Anweisungsfolge ohne den Guard ──

    test("Skript UP (Bestandsdatei gegen eindeutigen Index) laeuft ohne Guard durch: kein DROP INDEX nach dem Neubau") {
        val dir = Files.createTempDirectory("cli-rebuild-index-up-script")
        val db = database(dir, *uniqueIndexDb.toTypedArray())
        val (up, _) = scripts(dir, file(uniqueColumn), db)

        Files.readString(up) shouldNotContain "DROP INDEX"
        exec(db, *sqlStatements(up).toTypedArray())

        indexCount(db, "ix_u") shouldBe "0"
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 't'") shouldContain "UNIQUE"
    }

    test("Skript DOWN stellt den eindeutigen Index genau einmal wieder her, der Katalog gleicht dem Stand vor dem Lauf") {
        val dir = Files.createTempDirectory("cli-rebuild-index-down-script")
        val db = database(dir, *uniqueIndexDb.toTypedArray())
        val before = structure(db)
        val (up, down) = scripts(dir, file(uniqueColumn), db)

        exec(db, *sqlStatements(up).toTypedArray())
        sqlStatements(down).count { it.startsWith("CREATE UNIQUE INDEX") } shouldBe 1
        exec(db, *sqlStatements(down).toTypedArray())

        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_u'") shouldContain "CREATE UNIQUE INDEX"
        structure(db) shouldBe before
    }

    // ── P0.1 / P0.2: --execute ──

    test("Bestandsdatei gegen eindeutigen Index mit Fremdschluessel auf die Spalte: --execute Exit 0, zweiter Lauf 0") {
        val dir = Files.createTempDirectory("cli-rebuild-index-fk")
        val db = database(
            dir,
            "CREATE TABLE p (id INTEGER NOT NULL PRIMARY KEY)",
            "CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY, u INTEGER, " +
                "CONSTRAINT fk_t_u FOREIGN KEY (u) REFERENCES p (id))",
            "CREATE UNIQUE INDEX ix_u ON t (u)",
            "INSERT INTO p (id) VALUES (1)",
            "INSERT INTO t (id, u) VALUES (1, 1)",
        )
        val source = file(
            uniqueColumn,
            other = "  p:\n    columns:\n      id: { type: integer, required: true }\n    primary_key: [id]\n",
            constraints = "    constraints:\n      - name: fk_t_u\n        type: foreign_key\n        columns: [u]\n" +
                "        references: { table: p, columns: [id] }\n",
        )

        val (code, _) = execute(dir, source, db, "up")

        code shouldBe 0
        indexCount(db, "ix_u") shouldBe "0"
        scalar(db, "SELECT count(*) FROM t") shouldBe "1"
        secondRun(dir, source, db) shouldBe 0
    }

    // ── P0.4: neuer Index neben einem Neubau ──

    test("neuer Index neben einem Nullability-Neubau: --execute Exit 0, Index einmal angelegt, zweiter Lauf 0") {
        val dir = Files.createTempDirectory("cli-rebuild-index-add")
        val db = database(dir, textTableDb)
        val after = file(required(), index("- { name: ix_b, columns: [b] }"))

        val (code, _) = execute(dir, after, db, "up")

        code shouldBe 0
        indexCount(db, "ix_b") shouldBe "1"
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 't'") shouldContain "\"a\" TEXT NOT NULL"
        secondRun(dir, after, db) shouldBe 0
    }

    // ── P0.5: entfallender Index neben einem Neubau ──

    test("entfallender Index neben einem Nullability-Neubau: --execute Exit 0, Index weg, zweiter Lauf 0") {
        val dir = Files.createTempDirectory("cli-rebuild-index-drop")
        val db = database(dir, textTableDb, "CREATE INDEX ix_b ON t (b)")
        val after = file(required())

        val (code, _) = execute(dir, after, db, "up")

        code shouldBe 0
        indexCount(db, "ix_b") shouldBe "0"
        secondRun(dir, after, db) shouldBe 0
    }

    // ── P0.6: unveraenderter Index im Neubau ──

    test("unveraenderter Index im Neubau bleibt erhalten") {
        val dir = Files.createTempDirectory("cli-rebuild-index-keep")
        val db = database(dir, textTableDb, "CREATE INDEX ix_b ON t (b)")
        val after = file(required(), index("- { name: ix_b, columns: [b] }"))

        val (code, _) = execute(dir, after, db, "up")

        code shouldBe 0
        indexCount(db, "ix_b") shouldBe "1"
        secondRun(dir, after, db) shouldBe 0
    }

    // ── Rollback nach dem Fix ──

    for ((label, dbStatements, after) in listOf(
        Triple("neuer Index", listOf(textTableDb), file(required(), index("- { name: ix_b, columns: [b] }"))),
        Triple("entfallender Index", listOf(textTableDb, "CREATE INDEX ix_b ON t (b)"), file(required())),
    )) {
        test("schema rollback nach Neubau mit $label stellt den Stand vor dem Lauf wieder her") {
            val dir = Files.createTempDirectory("cli-rebuild-index-rollback")
            val db = database(dir, *dbStatements.toTypedArray())
            val start = structure(db)
            val (up, down) = scripts(dir, after, db)

            exec(db, *sqlStatements(up).toTypedArray())
            run(
                "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
                "--execute", "--allow-destructive",
            ) shouldBe 0

            structure(db) shouldBe start
        }
    }

    // ── Grenzen, die bleiben ──

    test("Neubau plus AddColumn einer anderen Tabelle: --execute bleibt Exit 8, Datenbank unveraendert") {
        val dir = Files.createTempDirectory("cli-rebuild-index-foreign")
        val db = database(dir, *uniqueIndexDb.toTypedArray(), "CREATE TABLE x (id INTEGER NOT NULL PRIMARY KEY)")
        val before = catalog(db)
        val source = file(
            uniqueColumn,
            other = "  x:\n    columns:\n      id: { type: integer, required: true }\n      e: { type: text }\n" +
                "    primary_key: [id]\n",
        )

        val (code, report) = execute(dir, source, db, "up")

        code shouldBe 8
        Files.readString(report) shouldContain "TRANSACTION_SCOPE_UNSUPPORTED"
        catalog(db) shouldBe before
    }

    // ── FULLTEXT-Index derselben Tabelle neben einem Neubau ──

    val ftIndex = index("- { name: ft_b, type: fulltext, columns: [b] }")

    /** Die FTS5-Objekte, die `schema generate` fuer einen `FULLTEXT`-Index auf `t(b)` anlegt. */
    fun ftsDb(column: String = "b") = listOf(
        "CREATE VIRTUAL TABLE \"ft_b\" USING fts5(\"$column\", content='t')",
        "INSERT INTO \"ft_b\"(\"ft_b\") VALUES('rebuild')",
        "CREATE TRIGGER \"ft_b_ai\" AFTER INSERT ON \"t\" BEGIN INSERT INTO \"ft_b\"(rowid, \"$column\") " +
            "VALUES (new.rowid, new.\"$column\"); END",
        "CREATE TRIGGER \"ft_b_ad\" AFTER DELETE ON \"t\" BEGIN INSERT INTO \"ft_b\"(\"ft_b\", rowid, \"$column\") " +
            "VALUES('delete', old.rowid, old.\"$column\"); END",
        "CREATE TRIGGER \"ft_b_au\" AFTER UPDATE ON \"t\" BEGIN INSERT INTO \"ft_b\"(\"ft_b\", rowid, \"$column\") " +
            "VALUES('delete', old.rowid, old.\"$column\"); INSERT INTO \"ft_b\"(rowid, \"$column\") " +
            "VALUES (new.rowid, new.\"$column\"); END",
    )

    fun ftsObjects(db: Path) = scalar(db, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'ft_b%'").toInt()

    fun ftsTriggers(db: Path) = scalar(db, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'ft_b_a%'").toInt()

    fun warnings(report: Path, code: String) = Regex("\"code\":\"$code\"").findAll(Files.readString(report)).count()

    fun ftsColumns(db: Path) = scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ft_b'")

    /**
     * Hinweg mit `--execute` (Exit 0, danach `check`), zweiter Lauf ohne Operationen, danach der Rueckweg: `down.sql`
     * ueber `schema rollback` bringt den ganzen Katalog (FTS5-Tabelle, Schattentabellen, Trigger) auf den Stand vor dem Lauf.
     */
    fun cycle(
        label: String,
        dbStatements: List<String>,
        after: String,
        warning: Pair<String, Int>? = null,
        check: (Path) -> Unit,
    ) {
        test("FULLTEXT neben einem Neubau, $label: --execute Exit 0, zweiter Lauf 0, schema rollback stellt den Katalog her") {
            val dir = Files.createTempDirectory("cli-rebuild-fulltext")
            val db = database(dir, *dbStatements.toTypedArray())
            val start = structure(db)
            val (_, down) = scripts(dir, after, db)

            val (code, report) = execute(dir, after, db, "up")

            code shouldBe 0
            check(db)
            warning?.let { (c, n) -> warnings(report, c) shouldBe n }
            secondRun(dir, after, db) shouldBe 0
            run(
                "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
                "--execute", "--allow-destructive",
            ) shouldBe 0
            structure(db) shouldBe start
        }
    }

    val withoutRowid = "    metadata:\n      without_rowid: true\n"

    val ftUniqueDb = listOf(
        "CREATE TABLE t (id INTEGER NOT NULL, u INTEGER, a TEXT, b TEXT, PRIMARY KEY (id))",
        "CREATE UNIQUE INDEX ix_u ON t (u)",
    )

    fun hasFts(db: Path) {
        ftsObjects(db) shouldBe 8
        ftsTriggers(db) shouldBe 3
    }

    cycle(
        "Fall 1: Neubau (unique) und neuer Index",
        ftUniqueDb,
        file("      u: { type: integer, unique: true }\n      a: { type: text }\n      b: { type: text }\n", ftIndex),
    ) { hasFts(it) }

    cycle("Fall 2: Nullability-Neubau und neuer Index", listOf(textTableDb), file(required(), ftIndex)) { hasFts(it) }

    cycle("Fall 3: Neubau und entfallender Index", listOf(textTableDb) + ftsDb(), file(required())) {
        ftsObjects(it) shouldBe 0
    }

    cycle(
        "Fall 4: Neubau und geaenderter Index",
        listOf(textTableDb) + ftsDb(),
        file(required(), index("- { name: ft_b, type: fulltext, columns: [a] }")),
    ) {
        hasFts(it)
        ftsColumns(it) shouldContain "\"a\""
    }

    cycle(
        "Fall 10: unveraenderter Plain-Index und Fremdschluessel",
        listOf(
            "CREATE TABLE p (id INTEGER NOT NULL PRIMARY KEY)",
            "CREATE TABLE t (id INTEGER NOT NULL, u INTEGER, a TEXT, b TEXT, PRIMARY KEY (id), " +
                "CONSTRAINT fk_t_u FOREIGN KEY (u) REFERENCES p (id))",
            "CREATE INDEX ix_b ON t (b)",
        ),
        file(
            required(),
            index("- { name: ix_b, columns: [b] }", "- { name: ft_b, type: fulltext, columns: [b] }"),
            other = "  p:\n    columns:\n      id: { type: integer, required: true }\n    primary_key: [id]\n",
            constraints = "    constraints:\n      - name: fk_t_u\n        type: foreign_key\n        columns: [u]\n" +
                "        references: { table: p, columns: [id] }\n",
        ),
    ) {
        hasFts(it)
        indexCount(it, "ix_b") shouldBe "1"
        scalar(it, "SELECT sql FROM sqlite_master WHERE name = 't'") shouldContain "fk_t_u"
    }

    /**
     * FTS5 nicht baubar: der Neubau laeuft mit genau einer `W132` und ohne FTS5. Die Datei fuehrt den Index weiter, die
     * Datenbank kann ihn nicht tragen; der Nachvergleich meldet das wie bei jedem `FULLTEXT` ohne Neubau
     * (`POST_EXECUTE_DRIFT`, Exit 5), nicht den Scope-Blocker. Der Rueckweg stellt den Katalog her.
     */
    fun degraded(label: String, dbStatements: List<String>, after: String) {
        test("FULLTEXT neben einem Neubau, $label: ein Strom, W132 genau einmal, kein FTS5, der Rueckweg stellt den Katalog her") {
            val dir = Files.createTempDirectory("cli-rebuild-fulltext-degraded")
            val db = database(dir, *dbStatements.toTypedArray())
            val start = structure(db)
            val (_, down) = scripts(dir, after, db)

            val (code, report) = execute(dir, after, db, "up")

            val text = Files.readString(report)
            text shouldNotContain "TRANSACTION_SCOPE_UNSUPPORTED"
            text shouldContain "POST_EXECUTE_DRIFT"
            code shouldBe 5
            warnings(report, "W132") shouldBe 1
            ftsObjects(db) shouldBe 0
            scalar(db, "SELECT sql FROM sqlite_master WHERE name = 't'") shouldContain "\"a\" TEXT NOT NULL"
            exec(db, *sqlStatements(down).toTypedArray())
            structure(db) shouldBe start
        }
    }

    degraded(
        "Fall 8: WITHOUT ROWID",
        listOf("CREATE TABLE t (id INTEGER NOT NULL, u INTEGER, a TEXT, b TEXT, PRIMARY KEY (id)) WITHOUT ROWID"),
        file(required(), ftIndex, meta = withoutRowid),
    )

    degraded(
        "Fall 9: reservierter Spaltenname rank",
        listOf("CREATE TABLE t (id INTEGER NOT NULL, u INTEGER, a TEXT, b TEXT, rank TEXT, PRIMARY KEY (id))"),
        file(required("      rank: { type: text }\n"), index("- { name: ft_b, type: fulltext, columns: [rank] }")),
    )

    /**
     * Gegenprobe ohne Neubau: die Tabelle stimmt mit der Datei ueberein, nur der `FULLTEXT`-Index fehlt. Der Exit 5
     * (`POST_EXECUTE_DRIFT`) stammt aus der Drift-Pruefung, nicht aus dem Neubau; der zweite Lauf zeigt weiter die
     * Operation `AddIndex`.
     */
    fun degradedWithoutRebuild(label: String, tableDb: String, after: String) {
        test("FULLTEXT ohne Neubau, $label: --execute Exit 5 (POST_EXECUTE_DRIFT), kein Neubau, zweiter Lauf zeigt 1 Operation") {
            val dir = Files.createTempDirectory("cli-fulltext-degraded-no-rebuild")
            val db = database(dir, tableDb)
            val tableBefore = scalar(db, "SELECT sql FROM sqlite_master WHERE name = 't'")

            val (code, report) = execute(dir, after, db, "up")

            Files.readString(report) shouldContain "POST_EXECUTE_DRIFT"
            code shouldBe 5
            ftsObjects(db) shouldBe 0
            scalar(db, "SELECT sql FROM sqlite_master WHERE name = 't'") shouldBe tableBefore
            val again = dir.resolve("again.json")
            run(
                "schema", "migrate", "--source", write(dir, "again.yaml", after).toString(),
                "--target", "db:sqlite://$db", "--output", dir.resolve("again.sql").toString(), "--report", again.toString(),
            ) shouldBe 0
            operations(again) shouldBe 1
        }
    }

    degradedWithoutRebuild(
        "Fall 8: WITHOUT ROWID",
        "CREATE TABLE t (id INTEGER NOT NULL, u INTEGER, a TEXT NOT NULL, b TEXT, PRIMARY KEY (id)) WITHOUT ROWID",
        file(required(), ftIndex, meta = withoutRowid),
    )

    degradedWithoutRebuild(
        "Fall 9: reservierter Spaltenname rank",
        "CREATE TABLE t (id INTEGER NOT NULL, u INTEGER, a TEXT NOT NULL, b TEXT, rank TEXT, PRIMARY KEY (id))",
        file(required("      rank: { type: text }\n"), index("- { name: ft_b, type: fulltext, columns: [rank] }")),
    )

    cycle(
        "Kontrolle: Rowid-Datenbank, Datei WITHOUT ROWID (der Neubau bleibt Rowid, FTS5 wird aufgebaut)",
        listOf(textTableDb),
        file(required(), ftIndex, meta = withoutRowid),
        warning = "W164" to 1,
    ) { hasFts(it) }

    // ── Skript ohne Guard: keine Doppelanlage ──

    test("Skript UP und DOWN (Fall 1) laufen ohne Guard durch, FTS5 wird je Richtung hoechstens einmal angelegt") {
        val dir = Files.createTempDirectory("cli-rebuild-fulltext-script-add")
        val db = database(dir, *ftUniqueDb.toTypedArray())
        val start = structure(db)
        val after = file("      u: { type: integer, unique: true }\n      a: { type: text }\n      b: { type: text }\n", ftIndex)
        val (up, down) = scripts(dir, after, db)

        sqlStatements(up).count { it.startsWith("CREATE VIRTUAL TABLE") } shouldBe 1
        exec(db, *sqlStatements(up).toTypedArray())
        hasFts(db)
        sqlStatements(down).count { it.startsWith("CREATE VIRTUAL TABLE") } shouldBe 0
        exec(db, *sqlStatements(down).toTypedArray())

        structure(db) shouldBe start
    }

    test("Skript DOWN (Fall 3) legt FTS5 genau einmal an und laeuft ohne Guard durch") {
        val dir = Files.createTempDirectory("cli-rebuild-fulltext-script-drop")
        val db = database(dir, textTableDb, *ftsDb().toTypedArray())
        val start = structure(db)
        val (up, down) = scripts(dir, file(required()), db)

        sqlStatements(up).count { it.startsWith("CREATE VIRTUAL TABLE") } shouldBe 0
        exec(db, *sqlStatements(up).toTypedArray())
        ftsObjects(db) shouldBe 0
        sqlStatements(down).count { it.startsWith("CREATE VIRTUAL TABLE") } shouldBe 1
        exec(db, *sqlStatements(down).toTypedArray())

        structure(db) shouldBe start
    }

    test("Skript UP und DOWN (Fall 4) legen FTS5 je Richtung genau einmal an") {
        val dir = Files.createTempDirectory("cli-rebuild-fulltext-script-change")
        val db = database(dir, textTableDb, *ftsDb().toTypedArray())
        val start = structure(db)
        val after = file(required(), index("- { name: ft_b, type: fulltext, columns: [a] }"))
        val (up, down) = scripts(dir, after, db)

        sqlStatements(up).count { it.startsWith("CREATE VIRTUAL TABLE") } shouldBe 1
        sqlStatements(down).count { it.startsWith("CREATE VIRTUAL TABLE") } shouldBe 1
        exec(db, *sqlStatements(up).toTypedArray())
        exec(db, *sqlStatements(down).toTypedArray())

        structure(db) shouldBe start
    }

    // ── Gegenproben ──

    test("FULLTEXT-Index einer anderen Tabelle neben einem Neubau: --execute bleibt Exit 8, Datenbank unveraendert") {
        val dir = Files.createTempDirectory("cli-rebuild-index-fulltext-foreign")
        val db = database(
            dir, *uniqueIndexDb.toTypedArray(),
            "CREATE TABLE x (id INTEGER NOT NULL PRIMARY KEY, e TEXT)",
        )
        val before = catalog(db)
        val source = file(
            uniqueColumn,
            other = "  x:\n    columns:\n      id: { type: integer, required: true }\n      e: { type: text }\n" +
                "    primary_key: [id]\n    indices:\n      - { name: ft_e, type: fulltext, columns: [e] }\n",
        )

        val (code, report) = execute(dir, source, db, "up")

        code shouldBe 8
        Files.readString(report) shouldContain "TRANSACTION_SCOPE_UNSUPPORTED"
        catalog(db) shouldBe before
    }

    test("Neubau mit unveraendertem FULLTEXT-Index (Gegenprobe Fall 5): Exit 0, FTS5 samt Triggern, zweiter Lauf 0") {
        val dir = Files.createTempDirectory("cli-rebuild-fulltext-keep")
        val db = database(dir, textTableDb, *ftsDb().toTypedArray())
        val after = file(required(), ftIndex)

        execute(dir, after, db, "up").first shouldBe 0

        hasFts(db)
        secondRun(dir, after, db) shouldBe 0
    }

    test("neuer FULLTEXT-Index ohne Neubau (Gegenprobe Fall 6): Exit 0, zweiter Lauf 0") {
        val dir = Files.createTempDirectory("cli-rebuild-fulltext-plain")
        val db = database(dir, textTableDb)
        val after = file(required().replace("a: { type: text, required: true }", "a: { type: text }"), ftIndex)

        execute(dir, after, db, "up").first shouldBe 0

        hasFts(db)
        secondRun(dir, after, db) shouldBe 0
    }
})
