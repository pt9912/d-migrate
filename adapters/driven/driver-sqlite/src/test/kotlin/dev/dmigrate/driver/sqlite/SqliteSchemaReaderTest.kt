package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.connection.ConnectionPool
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.Connection
import java.sql.DriverManager

class SqliteSchemaReaderTest : FunSpec({

    val reader = SqliteSchemaReader()

    fun pool(conn: Connection) = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = conn
        override fun activeConnections(): Int = 1
        override fun close() {}
    }

    fun withDb(vararg statements: String, block: (ConnectionPool) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                for (sql in statements) {
                    stmt.execute(sql.trim())
                }
            }
            block(pool(conn))
        }
    }

    // ── Canonical name/version ──────────────────

    test("reverse name and version follow canonical format") {
        withDb("CREATE TABLE t (id INTEGER PRIMARY KEY)") { pool ->
            val result = reader.read(pool)
            result.schema.name shouldBe "__dmigrate_reverse__:sqlite:schema=main"
            result.schema.version shouldBe "0.0.0-reverse"
            ReverseScopeCodec.isReverseGenerated(result.schema.name, result.schema.version) shouldBe true
        }
    }

    test("reverse marker set is parseable from schema document alone") {
        withDb("CREATE TABLE t (id INTEGER PRIMARY KEY)") { pool ->
            val result = reader.read(pool)
            val scope = ReverseScopeCodec.parseScope(result.schema.name)
            scope["dialect"] shouldBe "sqlite"
            scope["schema"] shouldBe "main"
        }
    }

    test("reverse scope with structural separators round-trips correctly") {
        // SQLite always uses schema=main, but verify the codec handles
        // hypothetical separator characters in schema names
        val encoded = ReverseScopeCodec.sqliteName("sch;ema=test:1")
        val scope = ReverseScopeCodec.parseScope(encoded)
        scope["dialect"] shouldBe "sqlite"
        scope["schema"] shouldBe "sch;ema=test:1"
        ReverseScopeCodec.isReverseGenerated(encoded, ReverseScopeCodec.REVERSE_VERSION) shouldBe true
    }

    // ── Basic table with columns ────────────────

    test("reads table with columns, PK and types") {
        withDb("""
            CREATE TABLE users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                email VARCHAR(254),
                age INTEGER DEFAULT 0
            )
        """) { pool ->
            val result = reader.read(pool)
            val users = result.schema.tables["users"]!!

            users.primaryKey shouldBe listOf("id")
            users.columns shouldContainKey "id"
            users.columns shouldContainKey "name"
            users.columns shouldContainKey "email"

            // id is AUTOINCREMENT → Identifier
            users.columns["id"]!!.type shouldBe NeutralType.Identifier(autoIncrement = true)

            // name is NOT NULL but NOT in PK → required=true
            users.columns["name"]!!.required shouldBe true
            users.columns["name"]!!.type shouldBe NeutralType.Text()

            // email has max_length
            users.columns["email"]!!.type shouldBe NeutralType.Text(maxLength = 254)

            // age has default
            users.columns["age"]!!.default shouldBe DefaultValue.NumberLiteral(0L)
        }
    }

    // ── PK-implicit required/unique not duplicated ──

    test("PK columns do not have redundant required or unique") {
        withDb("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL)") { pool ->
            val result = reader.read(pool)
            val t = result.schema.tables["t"]!!

            // PK column: required and unique must NOT be set
            t.columns["id"]!!.required shouldBe false
            t.columns["id"]!!.unique shouldBe false

            // Non-PK NOT NULL column: required MUST be set
            t.columns["name"]!!.required shouldBe true
        }
    }

    // ── Single-column UNIQUE on ColumnDefinition ──

    test("single-column UNIQUE is lifted to ColumnDefinition.unique") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT)",
            "CREATE UNIQUE INDEX idx_email ON t (email)",
        ) { pool ->
            val result = reader.read(pool)
            result.schema.tables["t"]!!.columns["email"]!!.unique shouldBe true
        }
    }

    test("partial single-column UNIQUE stays as index with predicate") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT, deleted_at TEXT)",
            "CREATE UNIQUE INDEX idx_active_email ON t (email) WHERE deleted_at IS NULL",
        ) { pool ->
            val result = reader.read(pool)
            val t = result.schema.tables["t"]!!

            t.columns["email"]!!.unique shouldBe false
            t.indices.single() shouldBe IndexDefinition(
                name = "idx_active_email",
                columns = listOf(IndexColumn("email")),
                unique = true,
                where = "deleted_at IS NULL",
            )
        }
    }

    // ── Single-column FK constraint ──

    test("single-column FK is preserved as table constraint") {
        withDb(
            "CREATE TABLE parent (id INTEGER PRIMARY KEY)",
            "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id) ON DELETE CASCADE)",
        ) { pool ->
            val result = reader.read(pool)
            val child = result.schema.tables["child"]!!
            child.columns["parent_id"]!!.references shouldBe null
            child.constraints.any {
                it.type == ConstraintType.FOREIGN_KEY &&
                    it.columns == listOf("parent_id") &&
                    it.references!!.table == "parent" &&
                    it.references!!.columns == listOf("id") &&
                    it.references!!.onDelete == ReferentialAction.CASCADE
            } shouldBe true
        }
    }

    // ── Multi-column constraints stay at constraint level ──

    test("multi-column FK stays at constraint level") {
        withDb(
            "CREATE TABLE parent (a INTEGER, b INTEGER, PRIMARY KEY (a, b))",
            "CREATE TABLE child (id INTEGER PRIMARY KEY, pa INTEGER, pb INTEGER, FOREIGN KEY (pa, pb) REFERENCES parent(a, b))",
        ) { pool ->
            val result = reader.read(pool)
            val child = result.schema.tables["child"]!!
            child.columns["pa"]!!.references shouldBe null
            child.constraints.any { it.type == ConstraintType.FOREIGN_KEY } shouldBe true
        }
    }

    // ── WITHOUT ROWID ───────────────────────────

    test("WITHOUT ROWID is captured in TableMetadata") {
        withDb("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID") { pool ->
            val result = reader.read(pool)
            result.schema.tables["kv"]!!.metadata shouldNotBe null
            result.schema.tables["kv"]!!.metadata!!.withoutRowid shouldBe true
        }
    }

    // ── AUTOINCREMENT detection ─────────────────

    test("AUTOINCREMENT only for INTEGER PRIMARY KEY AUTOINCREMENT") {
        withDb(
            "CREATE TABLE with_ai (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)",
            "CREATE TABLE without_ai (id INTEGER PRIMARY KEY, name TEXT)",
        ) { pool ->
            val result = reader.read(pool)
            result.schema.tables["with_ai"]!!.columns["id"]!!.type shouldBe
                NeutralType.Identifier(autoIncrement = true)
            result.schema.tables["without_ai"]!!.columns["id"]!!.type shouldBe
                NeutralType.Integer
        }
    }

    // ── Virtual tables skipped ──────────────────

    test("virtual table is skipped with code S100") {
        withDb(
            "CREATE TABLE normal (id INTEGER PRIMARY KEY)",
            "CREATE VIRTUAL TABLE search USING fts5(content)",
        ) { pool ->
            val result = reader.read(pool)
            result.schema.tables shouldContainKey "normal"
            result.schema.tables.keys.contains("search") shouldBe false
            result.skippedObjects shouldHaveSize 1
            result.skippedObjects[0].name shouldBe "search"
            result.skippedObjects[0].code shouldBe "S100"
        }
    }

    // ── CHECK constraints from CREATE TABLE SQL ──

    test("named CHECK constraints are read from CREATE TABLE SQL") {
        withDb("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER, CONSTRAINT chk_age CHECK (age > 0))") { pool ->
            val result = reader.read(pool)
            val t = result.schema.tables["t"]!!
            t.constraints.any { it.type == ConstraintType.CHECK && it.name == "chk_age" } shouldBe true
        }
    }

    // ── sqlite_autoindex suppressed ─────────────

    test("sqlite_autoindex backing indices are suppressed") {
        withDb("""
            CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE)
        """) { pool ->
            val result = reader.read(pool)
            val t = result.schema.tables["t"]!!
            // The UNIQUE constraint creates a sqlite_autoindex — should not appear
            t.indices.none { it.name?.startsWith("sqlite_autoindex_") == true } shouldBe true
        }
    }

    test("index descending direction is read from index_xinfo") {
        withDb(
            "CREATE TABLE orders (id INTEGER PRIMARY KEY, created_at TEXT)",
            "CREATE INDEX idx_orders_created ON orders (created_at DESC, id ASC)",
        ) { pool ->
            val result = reader.read(pool)
            val index = result.schema.tables.getValue("orders").indices.single()

            index.columns shouldBe listOf(
                IndexColumn("created_at", IndexSortDirection.DESC),
                IndexColumn("id"),
            )
        }
    }

    // ── Views under include flag ────────────────

    test("views are read when includeViews is true") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)",
            "CREATE VIEW v AS SELECT name FROM t",
        ) { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeViews = true))
            result.schema.views shouldContainKey "v"
        }
    }

    test("views are not read when includeViews is false") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)",
            "CREATE VIEW v AS SELECT name FROM t",
        ) { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeViews = false))
            result.schema.views.size shouldBe 0
        }
    }

    // ── Triggers under include flag ─────────────

    test("triggers are read when includeTriggers is true") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)",
            "CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END",
        ) { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeTriggers = true))
            result.schema.triggers.isNotEmpty() shouldBe true
        }
    }

    test("triggers are not read when includeTriggers is false") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)",
            "CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END",
        ) { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeTriggers = false))
            result.schema.triggers.size shouldBe 0
        }
    }

    // ── Trigger reverse-read fidelity (parser-routed) ──────────

    test("trigger reverse-read populates timing, event, body without trailing semi") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY)",
            "CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END",
        ) { pool ->
            val triggers = reader.read(pool, SchemaReadOptions(includeTriggers = true)).schema.triggers
            val trg = triggers.values.single()
            trg.timing shouldBe TriggerTiming.AFTER
            trg.events shouldBe setOf(TriggerEvent.INSERT)
            trg.table shouldBe "t"
            // Renderer always appends `;\nEND;`, so the parser must not store
            // the trailing `;` of its own — otherwise round-trip render
            // would double the terminator.
            trg.body shouldBe "SELECT 1"
        }
    }

    test("trigger reverse-read populates WHEN clause as condition") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)",
            """
            CREATE TRIGGER trg AFTER UPDATE ON t
              FOR EACH ROW WHEN NEW.name <> OLD.name
            BEGIN
              SELECT 1;
            END
            """.trimIndent(),
        ) { pool ->
            val triggers = reader.read(pool, SchemaReadOptions(includeTriggers = true)).schema.triggers
            val trg = triggers.values.single()
            trg.condition shouldBe "NEW.name <> OLD.name"
        }
    }

    test("INSTEAD OF trigger on view is parsed as INSTEAD_OF") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY)",
            "CREATE VIEW v AS SELECT id FROM t",
            "CREATE TRIGGER trg INSTEAD OF DELETE ON v BEGIN SELECT 1; END",
        ) { pool ->
            val triggers = reader.read(pool, SchemaReadOptions(includeTriggers = true)).schema.triggers
            triggers.values.single().timing shouldBe TriggerTiming.INSTEAD_OF
        }
    }

    test("UPDATE OF cols emits R213 WARNING and reverse-reads as plain UPDATE") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT, b TEXT)",
            "CREATE TRIGGER trg AFTER UPDATE OF a, b ON t BEGIN SELECT 1; END",
        ) { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeTriggers = true))
            result.schema.triggers.values.single().events shouldBe setOf(TriggerEvent.UPDATE)
            val r213 = result.notes.single { it.code == "R213" }
            r213.severity shouldBe SchemaReadSeverity.WARNING
            r213.objectName shouldBe "trg"
        }
    }

    test("multi-statement trigger body preserves inner `;` separators") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY)",
            "CREATE TABLE log (id INTEGER, ts TEXT)",
            """
            CREATE TRIGGER trg AFTER INSERT ON t
            BEGIN
              INSERT INTO log (id) VALUES (NEW.id);
              UPDATE log SET ts = CURRENT_TIMESTAMP WHERE id = NEW.id;
            END
            """.trimIndent(),
        ) { pool ->
            val trg = reader.read(pool, SchemaReadOptions(includeTriggers = true))
                .schema.triggers.values.single()
            // Inner `;` separators stay, only the trailing one before END
            // is stripped; outer whitespace is trimmed so the body starts
            // with the first statement.
            trg.body shouldBe "INSERT INTO log (id) VALUES (NEW.id);\n" +
                "  UPDATE log SET ts = CURRENT_TIMESTAMP WHERE id = NEW.id"
        }
    }

    test("trigger round-trip: read -> render -> apply -> read is bit-identical") {
        // Reverse → File-Write → Reverse with a real SQLite file. The
        // renderer writes the body back with `;\nEND;`; the parser must
        // not regrow a trailing `;` on the second read, otherwise round-
        // trip drifts to `SELECT 1;;` after a few cycles.
        //
        // File-based DB because SqliteSchemaReader.read closes the
        // borrowed connection via `.use`, and `:memory:` lives only on
        // the connection — a second read would see an empty DB.
        val tmp = java.nio.file.Files.createTempFile("sqlite-trigger-roundtrip", ".db")
        try {
            val url = "jdbc:sqlite:${tmp.toAbsolutePath()}"
            val filePool = object : ConnectionPool {
                override val dialect = DatabaseDialect.SQLITE
                override fun borrow(): Connection = DriverManager.getConnection(url)
                override fun activeConnections(): Int = 0
                override fun close() {}
            }
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
                    stmt.execute("CREATE TABLE log (id INTEGER, ts TEXT)")
                    stmt.execute(
                        """
                        CREATE TRIGGER trg_audit AFTER UPDATE ON t
                          FOR EACH ROW WHEN NEW.name <> OLD.name
                        BEGIN
                          UPDATE log SET ts = CURRENT_TIMESTAMP WHERE id = NEW.id;
                        END
                        """.trimIndent(),
                    )
                }
            }

            val first = reader.read(filePool, SchemaReadOptions(includeTriggers = true))
                .schema.triggers.values.single()

            // Render the parsed trigger back to DDL using the production
            // renderer-side builder. This is the "file-write" leg of the
            // round-trip — what the YAML schema codec would persist.
            val rendered = SqliteDiffSqlBuilders().createTriggerSql("trg_audit", first)!!

            // Re-apply: drop the original and recreate from the renderer
            // output. This proves the parser-renderer pair converges.
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP TRIGGER trg_audit")
                    stmt.execute(rendered)
                }
            }

            val second = reader.read(filePool, SchemaReadOptions(includeTriggers = true))
                .schema.triggers.values.single()
            second shouldBe first
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
    }


    // ── Unknown type produces note ──────────────

    test("unknown column type produces warning note") {
        withDb("CREATE TABLE t (id INTEGER PRIMARY KEY, data CUSTOMTYPE)") { pool ->
            val result = reader.read(pool)
            result.schema.tables["t"]!!.columns["data"]!!.type shouldBe NeutralType.Text()
            result.notes.any { it.code == "R201" && it.objectName == "t.data" } shouldBe true
        }
    }

    // ── SpatiaLite metadata tables skipped ──────

    test("SpatiaLite metadata tables are skipped with code S101") {
        withDb(
            "CREATE TABLE normal (id INTEGER PRIMARY KEY)",
            "CREATE TABLE geometry_columns (f_table_name TEXT, f_geometry_column TEXT)",
            "CREATE TABLE spatial_ref_sys (srid INTEGER PRIMARY KEY, auth_name TEXT)",
        ) { pool ->
            val result = reader.read(pool)
            result.schema.tables shouldContainKey "normal"
            result.schema.tables.keys.contains("geometry_columns") shouldBe false
            result.schema.tables.keys.contains("spatial_ref_sys") shouldBe false
            result.skippedObjects.any { it.name == "geometry_columns" && it.code == "S101" } shouldBe true
            result.skippedObjects.any { it.name == "spatial_ref_sys" && it.code == "S101" } shouldBe true
        }
    }

    // ── Geometry column type produces note ──────

    test("geometry column type maps to Geometry with note") {
        withDb("CREATE TABLE geo (id INTEGER PRIMARY KEY, location POINT)") { pool ->
            val result = reader.read(pool)
            val locType = result.schema.tables["geo"]!!.columns["location"]!!.type
            (locType is NeutralType.Geometry) shouldBe true
            result.notes.any { it.code == "R220" && it.objectName == "geo.location" } shouldBe true
        }
    }

    // ── Ownership: connection returned after read ──

    test("read completes successfully and returns result") {
        // Verifies the reader borrows and returns the connection within
        // pool.borrow().use { } — if it leaked, the in-memory DB would
        // be inaccessible. A successful read is the ownership proof.
        withDb("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)") { pool ->
            val result = reader.read(pool)
            result.schema.tables shouldContainKey "t"
            result.schema.tables["t"]!!.columns.size shouldBe 2
        }
    }
})
