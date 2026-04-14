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

    // ── Single-column FK on ColumnDefinition.references ──

    test("single-column FK is lifted to ColumnDefinition.references") {
        withDb(
            "CREATE TABLE parent (id INTEGER PRIMARY KEY)",
            "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id) ON DELETE CASCADE)",
        ) { pool ->
            val result = reader.read(pool)
            val ref = result.schema.tables["child"]!!.columns["parent_id"]!!.references!!
            ref.table shouldBe "parent"
            ref.column shouldBe "id"
            ref.onDelete shouldBe ReferentialAction.CASCADE
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
})
